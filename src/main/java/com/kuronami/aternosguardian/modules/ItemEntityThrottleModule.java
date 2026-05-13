package com.kuronami.aternosguardian.modules;

import com.kuronami.aternosguardian.config.HeapGuardianConfig;
import com.kuronami.aternosguardian.monitor.ThrottleLevel;
import com.kuronami.aternosguardian.monitor.ThrottleLevelChangedEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Tick throttle for ground-resting {@link ItemEntity} and
 * {@link ExperienceOrb} instances.
 *
 * <p>Mob farms generate enormous numbers of these — a typical iron farm
 * spits out ~100 item entities per minute, all of which tick every
 * tick by vanilla. The vast majority are just sitting on the ground
 * waiting either for a hopper or for their 5-minute despawn timer.
 * Their per-tick work is small but their COUNT is large; multiplied
 * out, item-entity ticking can be 20-30% of an entity-heavy server's
 * tick budget. Reducing that frees room for the heap to breathe.
 *
 * <p>Why a separate module from {@code EntityTickThrottleModule}? Two
 * reasons:
 * <ol>
 *   <li>The exemption rules differ. Items don't have a {@code Mob}
 *       interface, so {@link com.kuronami.aternosguardian.util.BossDetection}
 *       doesn't apply. Instead, we care about "is this near a player
 *       who could pick it up?" — a different check entirely.</li>
 *   <li>The starvation floor must be lower. Items have a 5-minute
 *       despawn timer; if we throttle their tick to once per 16 ticks
 *       at L3+, the despawn timer effectively runs at 1/16 speed too,
 *       which means our throttling produces MORE accumulated trash on
 *       the ground (the opposite of what we want). A short floor —
 *       full tick every 40 ticks (~2 seconds) — keeps the despawn
 *       timer progressing without losing the bulk of the savings.</li>
 * </ol>
 *
 * <h3>Always-allow conditions</h3>
 * <ul>
 *   <li>Item is in motion (delta-movement above MOVING_THRESHOLD) —
 *       a falling item or one bouncing off a slab needs accurate
 *       physics ticks.</li>
 *   <li>Item is in the air ({@code !onGround}) — falling, in water,
 *       etc.</li>
 *   <li>A player is within {@link #PICKUP_AWARE_RADIUS} blocks —
 *       throttling now would visibly delay pickup ("I walked over it
 *       and didn't get it").</li>
 * </ul>
 */
public class ItemEntityThrottleModule {

    /** Radius around any player where items always tick full. */
    private static final int PICKUP_AWARE_RADIUS = 24;
    private static final double PICKUP_AWARE_SQ =
        (double) PICKUP_AWARE_RADIUS * PICKUP_AWARE_RADIUS;

    /** Treat items with this much velocity as "moving" — keep ticking. */
    private static final double MOVING_THRESHOLD_SQ = 0.003 * 0.003;

    /**
     * Every Nth tick we let through a full tick regardless of bucket,
     * so the 6000-tick (~5min) despawn timer keeps progressing. With
     * a floor of 40 ticks, despawn at L4 runs at ~1/2 speed instead
     * of the 1/16 it would otherwise.
     */
    private static final int STARVATION_FLOOR_TICKS = 40;

    private volatile ThrottleLevel currentLevel = ThrottleLevel.NORMAL;

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        currentLevel = event.current();
    }

    @SubscribeEvent
    public void onEntityTickPre(EntityTickEvent.Pre event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_ITEM_THROTTLE.get())) {
            return;
        }
        if (currentLevel == ThrottleLevel.NORMAL) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // Falling / in motion items must keep ticking — physics correctness.
        if (!entity.onGround()) {
            return;
        }
        if (entity.getDeltaMovement().lengthSqr() > MOVING_THRESHOLD_SQ) {
            return;
        }
        // Don't throttle items a player could be about to walk into.
        // Pickup range is ~1 block but we use a generous radius because
        // a player running toward the item should still find it ticking.
        if (anyPlayerWithin(level, entity.position(), PICKUP_AWARE_SQ)) {
            return;
        }

        // Starvation floor: keep the despawn timer progressing even when
        // we're throttling aggressively. (gameTime + entityId) % N
        // distributes the full-tick frame across items.
        long gameTime = level.getGameTime();
        if ((gameTime + entity.getId()) % STARVATION_FLOOR_TICKS == 0) {
            return;
        }

        int interval = intervalFor(currentLevel);
        if (interval <= 1) {
            return;
        }
        if ((gameTime + entity.getId()) % interval != 0) {
            event.setCanceled(true);
        }
    }

    /** Tier-to-interval map. More aggressive than mob throttling because
     *  items resting on the ground really don't need 20Hz updates. */
    private int intervalFor(ThrottleLevel level) {
        return switch (level) {
            case NORMAL -> 1;
            case L1_MILD -> 4;
            case L2_HEAVY -> 8;
            case L3_AGGRESSIVE -> 16;
            case L4_EMERGENCY -> 32;
        };
    }

    private boolean anyPlayerWithin(ServerLevel level, Vec3 itemPos, double radiusSq) {
        for (Player player : level.players()) {
            if (player.position().distanceToSqr(itemPos) < radiusSq) {
                return true;
            }
        }
        return false;
    }
}
