package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.compat.CompatibilityCoordinator;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import com.kuronami.freeserversaver.monitor.ThrottleLevel;
import com.kuronami.freeserversaver.monitor.ThrottleLevelChangedEvent;
import com.kuronami.freeserversaver.util.BossDetection;
import com.kuronami.freeserversaver.util.SafetyGate;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Distance-based entity tick throttle.
 *
 * <p>The defining intervention of Heap Guardian — and the one I was
 * missing in Phase 1. Vanilla random ticks (the thing the old
 * {@code RandomTickModule} touched) are allocation-cheap; per-mob AI
 * tick is not. Modpack heap pressure comes overwhelmingly from
 * entities being ticked, not from crops growing. Throttling AI ticks
 * for far-away mobs gives us the heap headroom without the
 * "my farm stopped working" complaint that random-tick throttling
 * would produce.
 *
 * <p>Modeled on the public design of Where's my Brain (DAB) and
 * Immersive Optimization — distance-bucket each mob, multiply the
 * effective tick interval per bucket. Read their docs and source for
 * the original patterns; the code here is independent.
 *
 * <h3>Buckets</h3>
 * <pre>
 *   NEAR    &lt;32 blocks   — never throttle, combat must feel responsive
 *   MID     &lt;64 blocks   — throttle at L2+
 *   FAR     &lt;96 blocks   — throttle at L1+
 *   DISTANT ≥96 blocks   — always throttle once we're at L1+
 * </pre>
 *
 * <h3>Hysteresis</h3>
 * <p>A mob whose distance hovers near a bucket boundary would flip
 * buckets every tick if we used raw distance, producing a "blinking"
 * AI rate that's worse than no throttling. The boundaries are extended
 * by {@link #HYSTERESIS_BLOCKS} once a mob is in a slower bucket —
 * effectively making it slightly harder to escape into a faster bucket
 * than to fall into a slower one.
 *
 * <h3>Proximity snapshot</h3>
 * <p>Computing nearest-player-distance every tick for every mob is
 * itself an allocation cost (Vec3 distanceToSqr calls per mob).
 * Following the WMB pattern, we snapshot player positions once every
 * {@link #SNAPSHOT_INTERVAL_TICKS} (=5) ticks and reuse them.
 *
 * <h3>Starvation prevention</h3>
 * <p>Even in DISTANT bucket at L4, every mob runs a full tick once
 * every {@link #STARVATION_FLOOR_TICKS} ticks. Without this, AI state
 * machines that need periodic eviction (item pickup cooldown, target
 * forget, despawn timer) can stall indefinitely.
 *
 * <h3>Exemptions</h3>
 * <p>Bosses, important NPCs, and player-protected entities (named,
 * leashed, tamed, persistent) skip throttling entirely. See
 * {@link BossDetection}.
 *
 * <h3>EntityTickEvent.Pre cancellation</h3>
 * <p>NeoForge 1.21's {@code EntityTickEvent.Pre} fires from the head
 * of {@code LivingEntity.tick()}. If it's cancellable (implements
 * {@code ICancellableEvent}), cancelling it skips the rest of the tick.
 * If it isn't, this module won't compile — we'd fall back to a
 * mixin-based approach in a later phase. The current implementation
 * attempts the cancel; the build will tell us whether that's allowed.
 */
public class EntityTickThrottleModule {

    /** Bucket thresholds in blocks. Square these for distance-squared comparisons. */
    private static final double NEAR_RADIUS = 32.0;
    private static final double MID_RADIUS = 64.0;
    private static final double FAR_RADIUS = 96.0;

    private static final double NEAR_SQ = NEAR_RADIUS * NEAR_RADIUS;
    private static final double MID_SQ = MID_RADIUS * MID_RADIUS;
    private static final double FAR_SQ = FAR_RADIUS * FAR_RADIUS;

    /** Boundary deadband: a mob in a slower bucket needs to retreat this much before escaping. */
    private static final double HYSTERESIS_BLOCKS = 8.0;
    private static final double HYSTERESIS_NEAR_SQ = (NEAR_RADIUS + HYSTERESIS_BLOCKS) * (NEAR_RADIUS + HYSTERESIS_BLOCKS);
    private static final double HYSTERESIS_MID_SQ = (MID_RADIUS + HYSTERESIS_BLOCKS) * (MID_RADIUS + HYSTERESIS_BLOCKS);
    private static final double HYSTERESIS_FAR_SQ = (FAR_RADIUS + HYSTERESIS_BLOCKS) * (FAR_RADIUS + HYSTERESIS_BLOCKS);

    /** How often the player-position cache refreshes. 5 ticks = 4 Hz, plenty for AI bucketing. */
    private static final int SNAPSHOT_INTERVAL_TICKS = 5;

    /** Floor: every mob runs full AI at least once per N ticks regardless of bucket. */
    private static final int STARVATION_FLOOR_TICKS = 20;

    enum Bucket { NEAR, MID, FAR, DISTANT }

    private MinecraftServer server;
    private volatile ThrottleLevel currentLevel = ThrottleLevel.NORMAL;

    /** Cached player positions per dimension. Rebuilt every SNAPSHOT_INTERVAL_TICKS. */
    private final Map<ServerLevel, Vec3[]> playerSnapshots = new HashMap<>();
    private int ticksSinceSnapshot = 0;

    /** Last-known bucket per mob, used for hysteresis. {@code mob.getId()} keyed. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Bucket> lastBucket =
        new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        playerSnapshots.clear();
        lastBucket.clear();
        ticksSinceSnapshot = 0;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
        playerSnapshots.clear();
        lastBucket.clear();
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        currentLevel = event.current();
    }

    /**
     * Refresh the player-position snapshot once per {@link #SNAPSHOT_INTERVAL_TICKS}.
     * Doing this on {@code ServerTickEvent.Post} (instead of inside the mob tick hot
     * path) keeps the per-mob check to a few field reads.
     */
    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (server == null) return;

        ticksSinceSnapshot++;
        if (ticksSinceSnapshot < SNAPSHOT_INTERVAL_TICKS) return;
        ticksSinceSnapshot = 0;

        playerSnapshots.clear();
        for (ServerLevel level : server.getAllLevels()) {
            Vec3[] positions = level.players().stream()
                .map(ServerPlayer::position)
                .toArray(Vec3[]::new);
            playerSnapshots.put(level, positions);
        }
    }

    /**
     * Hot path: called for every {@code Mob.tick()} on the server.
     * Must be cheap. Three short branches and a single distance check
     * in the common case.
     */
    @SubscribeEvent
    public void onEntityTickPre(EntityTickEvent.Pre event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_ENTITY_TICK_THROTTLE.get())) {
            return;
        }
        // Competitor mod check: if a distance-bucket mob-throttle mod
        // (DAB / WMB / Immersive Optimization / OptimizeMod / etc.) is
        // installed, we step out of the lane entirely. See
        // CompatibilityCoordinator for rationale.
        if (CompatibilityCoordinator.yieldEntityTickThrottle()) {
            return;
        }
        // Another handler may have already cancelled this event. Don't
        // duplicate the distance compute — and don't reverse their
        // decision either.
        if (event.isCanceled()) {
            return;
        }
        // Throttling is opt-in at NORMAL — we don't want to introduce
        // any behavior change at all when the heap is fine.
        if (currentLevel == ThrottleLevel.NORMAL) {
            return;
        }
        // First gate: SafetyGate handles non-Mob always-excluded classes
        // (players, projectiles, lightning, TNT, EnderDragonPart, etc.)
        // and transient critical state. Cheap checks, short-circuit hard.
        if (SafetyGate.shouldSkipThrottle(event.getEntity())) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        // Server-side only. Client-side ticks (smooth interpolation,
        // rendering hooks) shouldn't be touched.
        if (mob.level().isClientSide()) {
            return;
        }
        // Standard exemption ladder. BossDetection short-circuits hard.
        if (BossDetection.shouldNeverTouch(mob)) {
            return;
        }

        // Starvation floor: every STARVATION_FLOOR_TICKS, let the mob
        // run a full tick regardless of bucket. Uses (gameTime + id) so
        // mobs distribute across ticks instead of all firing on the
        // same one.
        long gameTime = mob.level().getGameTime();
        if ((gameTime + mob.getId()) % STARVATION_FLOOR_TICKS == 0) {
            return;
        }

        Vec3[] positions = playerSnapshots.get((ServerLevel) mob.level());
        if (positions == null || positions.length == 0) {
            // No players in this dimension — no one is watching, throttle
            // hard. Effectively the same as DISTANT bucket.
            applyBucketSkip(event, Bucket.DISTANT, gameTime, mob.getId());
            return;
        }

        Bucket bucket = classifyWithHysteresis(mob, positions);
        applyBucketSkip(event, bucket, gameTime, mob.getId());
    }

    /**
     * Classify a mob into NEAR/MID/FAR/DISTANT, biased toward its
     * previous bucket via {@link #HYSTERESIS_BLOCKS}. A mob currently
     * in MID stays in MID until it crosses the NEAR boundary minus
     * the deadband on one side, or the MID-FAR boundary plus the
     * deadband on the other.
     */
    private Bucket classifyWithHysteresis(Mob mob, Vec3[] positions) {
        Vec3 mobPos = mob.position();
        double minSq = Double.MAX_VALUE;
        for (Vec3 p : positions) {
            double dSq = mobPos.distanceToSqr(p);
            if (dSq < minSq) minSq = dSq;
        }

        Bucket previous = lastBucket.getOrDefault(mob.getId(), Bucket.NEAR);

        // Pick thresholds based on the previous bucket — entering a slower
        // bucket uses the wider threshold (harder to leave), entering a
        // faster bucket uses the wider threshold (harder to enter).
        Bucket next;
        switch (previous) {
            case NEAR -> {
                if (minSq < NEAR_SQ) next = Bucket.NEAR;
                else if (minSq < MID_SQ) next = Bucket.MID;
                else if (minSq < FAR_SQ) next = Bucket.FAR;
                else next = Bucket.DISTANT;
            }
            case MID -> {
                if (minSq < HYSTERESIS_NEAR_SQ && minSq < NEAR_SQ) next = Bucket.NEAR;
                else if (minSq < MID_SQ) next = Bucket.MID;
                else if (minSq < FAR_SQ) next = Bucket.FAR;
                else next = Bucket.DISTANT;
            }
            case FAR -> {
                if (minSq < NEAR_SQ) next = Bucket.NEAR;
                else if (minSq < HYSTERESIS_MID_SQ && minSq < MID_SQ) next = Bucket.MID;
                else if (minSq < FAR_SQ) next = Bucket.FAR;
                else next = Bucket.DISTANT;
            }
            default -> { // DISTANT
                if (minSq < NEAR_SQ) next = Bucket.NEAR;
                else if (minSq < MID_SQ) next = Bucket.MID;
                else if (minSq < HYSTERESIS_FAR_SQ && minSq < FAR_SQ) next = Bucket.FAR;
                else next = Bucket.DISTANT;
            }
        }

        if (next != previous) {
            lastBucket.put(mob.getId(), next);
        }
        return next;
    }

    /**
     * Decide whether to cancel this tick based on the mob's bucket and
     * the current heap tier. The mapping is intentionally lazy at low
     * tiers — at L1 we only throttle the slowest two buckets, and even
     * those by a small factor.
     *
     * <p>Tier × bucket → interval mapping. Interval N means "tick every
     * Nth call." 1 = no throttle.
     * <pre>
     *               NEAR  MID   FAR   DISTANT
     *   L1_MILD     1     1     2     4
     *   L2_HEAVY    1     2     4     8
     *   L3_AGG      1     2     4     16
     *   L4_EMRG     1     4     8     32
     * </pre>
     */
    private void applyBucketSkip(EntityTickEvent.Pre event, Bucket bucket, long gameTime, int mobId) {
        int interval = intervalFor(bucket);
        if (interval <= 1) {
            return;
        }
        // Skew by mob id so mobs distribute their full-tick frames
        // instead of all running on the same one (which would create a
        // tick-time spike).
        if ((gameTime + mobId) % interval != 0) {
            event.setCanceled(true);
        }
    }

    private int intervalFor(Bucket bucket) {
        return switch (currentLevel) {
            case NORMAL -> 1; // unreachable, guarded above
            case L1_MILD -> switch (bucket) {
                case NEAR, MID -> 1;
                case FAR -> 2;
                case DISTANT -> 4;
            };
            case L2_HEAVY -> switch (bucket) {
                case NEAR -> 1;
                case MID -> 2;
                case FAR -> 4;
                case DISTANT -> 8;
            };
            case L3_AGGRESSIVE -> switch (bucket) {
                case NEAR -> 1;
                case MID -> 2;
                case FAR -> 4;
                case DISTANT -> 16;
            };
            case L4_EMERGENCY -> switch (bucket) {
                case NEAR -> 1;
                case MID -> 4;
                case FAR -> 8;
                case DISTANT -> 32;
            };
        };
    }
}
