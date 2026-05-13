package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import com.kuronami.freeserversaver.monitor.ThrottleLevel;
import com.kuronami.freeserversaver.monitor.ThrottleLevelChangedEvent;
import com.kuronami.freeserversaver.util.BossDetection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * On entry to {@link ThrottleLevel#L4_EMERGENCY}, sweep every loaded
 * level and force-{@link Entity#discard() discard} mobs that are
 * (a) far from any player and (b) safe to despawn.
 *
 * <p>This is the most invasive intervention in Heap Guardian — it
 * outright deletes entities that were behaving normally. Vanilla's
 * own despawn logic would eventually remove far mobs anyway, but
 * "eventually" runs on per-mob despawn timers spread across many
 * ticks. When the heap is at 85%+ we don't have many ticks left
 * before the GC eats the server. This module compresses that
 * cleanup into a single pass.
 *
 * <p>The sweep is gated to <em>L4 escalation only</em> — not every
 * tick at L4, and not at recovery (otherwise a flapping heap would
 * kill the same mobs over and over). The intent is "one big cleanup,
 * then let the rest of the system catch up."
 *
 * <p>Always-spare categories:
 * <ul>
 *   <li><strong>Named entities</strong> — player took the time to tag.</li>
 *   <li><strong>Persistence-required mobs</strong> — leashed, traded
 *       with, or otherwise flagged by vanilla/mods as non-despawnable.</li>
 *   <li><strong>Villagers, traders, golems</strong> — even if not
 *       formally persistence-required, killing these breaks gameplay
 *       in surprising ways (a villager workstation rebinds, an iron
 *       farm stops producing).</li>
 *   <li><strong>Tamed entities</strong> — handled via the
 *       {@code isPersistenceRequired} check on most mods, but doubled
 *       up here for safety.</li>
 *   <li><strong>Players</strong> — never iterated (we only loop
 *       {@link Mob} subclasses).</li>
 * </ul>
 *
 * <p>Distance check uses squared distance against each player's
 * position. {@link #MIN_DISTANCE_BLOCKS} of 64 = 4 chunks, which
 * matches vanilla's despawn-range floor for hostile mobs.
 */
public class DespawnModule {

    /** Mobs strictly farther than this from every player are eligible. */
    private static final int MIN_DISTANCE_BLOCKS = 64;
    private static final double MIN_DISTANCE_SQ =
        (double) MIN_DISTANCE_BLOCKS * MIN_DISTANCE_BLOCKS;

    private MinecraftServer server;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (server == null) {
            return;
        }
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_DESPAWN_SWEEP.get())) {
            return;
        }
        // Only act on the rising edge into L4 — once per crisis, not
        // continuously while there.
        if (event.current() != ThrottleLevel.L4_EMERGENCY || !event.isEscalation()) {
            return;
        }

        int killed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            killed += sweepLevel(level);
        }

        HeapGuardian.LOGGER.warn(
            "[Despawn] L4_EMERGENCY sweep removed {} far-from-player mobs to relieve heap pressure.",
            killed);
    }

    /**
     * Walk every entity in the level once. We're already in an emergency,
     * so a full iteration is acceptable — the alternative (waiting for
     * vanilla's per-mob timer) costs more in GC pause time.
     */
    private int sweepLevel(ServerLevel level) {
        int killed = 0;

        // Snapshot player positions once instead of querying inside the
        // inner loop — players don't move during a single tick handler,
        // and the lookup itself allocates a Vec3 per call.
        Vec3[] playerPositions = level.players().stream()
            .map(ServerPlayer::position)
            .toArray(Vec3[]::new);

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            if (!isSafeToDespawn(mob)) {
                continue;
            }
            if (!isFarFromAllPlayers(mob, playerPositions)) {
                continue;
            }
            mob.discard();
            killed++;

            if (Boolean.TRUE.equals(HeapGuardianConfig.VERBOSE_LOGGING.get())) {
                HeapGuardian.LOGGER.debug(
                    "[Despawn] Discarded {} in {} at {}",
                    mob.getType().getDescriptionId(),
                    level.dimension().location(),
                    mob.blockPosition());
            }
        }
        return killed;
    }

    /**
     * Apply the always-spare exception list. Returns {@code true} if the
     * mob can be discarded without breaking gameplay in a surprising way.
     *
     * <p>Delegates to {@link BossDetection#shouldNeverTouch} so the
     * exemption list stays consistent with {@code SpawnThrottleModule}
     * and {@code EntityTickThrottleModule} — adding a new boss class in
     * one place takes effect everywhere.
     */
    private boolean isSafeToDespawn(Mob mob) {
        return !BossDetection.shouldNeverTouch(mob);
    }

    private boolean isFarFromAllPlayers(Mob mob, Vec3[] playerPositions) {
        if (playerPositions.length == 0) {
            // No players in this dimension at all — every mob in it is
            // wasting heap. Safe to despawn.
            return true;
        }
        Vec3 mobPos = mob.position();
        for (Vec3 playerPos : playerPositions) {
            if (mobPos.distanceToSqr(playerPos) < MIN_DISTANCE_SQ) {
                return false;
            }
        }
        return true;
    }
}
