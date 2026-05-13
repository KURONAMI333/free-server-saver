package com.kuronami.freeserversaver.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.Vec3;

/**
 * Transient state checks — "what is happening to this entity right now"
 * that means we shouldn't throttle it this tick, regardless of distance
 * or boss-status.
 *
 * <p>Distinct from {@link BossDetection}, which answers "what KIND of
 * entity is this." BossDetection is about identity; SafetyGate is about
 * temporary state. A regular zombie that's currently attacking a player
 * must keep ticking even though it isn't a boss.
 *
 * <p>This catalog is derived from reading ServerCore's
 * {@code ActivationRange.java} (Paper / Aikar's Entity Activation Range
 * patch, GPL-3.0). The behavioral rules are the same as Paper's because
 * the problem space is the same — what state makes a mob "important
 * right now." Implementation here is independent.
 *
 * <h3>Why two classes?</h3>
 * <p>The split makes adding new rules cheap: a new boss class only
 * touches {@link BossDetection}; a new transient state (e.g. "mob is
 * sleeping" if a future MC adds that) only touches {@link SafetyGate}.
 * The hot-path call site in {@code EntityTickThrottleModule} reads as
 * a clean ladder of "is it a boss? is it in a critical state? is it
 * far enough?"
 */
public final class SafetyGate {

    /** Minimum movement delta to consider an entity "actively moving." */
    private static final double MOVING_THRESHOLD = 0.001;

    /** Mob is considered "in motion" when its delta-movement squared exceeds this. */
    private static final double MOB_MOTION_THRESHOLD_SQ = 0.005 * 0.005;

    /** Newly spawned entities (under this tickCount) always run full ticks. */
    private static final int NEW_ENTITY_TICK_FLOOR = 200; // ~10 seconds

    private SafetyGate() {}

    /**
     * True for entity classes that should never have their tick cancelled
     * regardless of distance. Different from {@link BossDetection#isBoss}
     * — that's for big-HP combat targets; this is for entities whose
     * behavior would visibly break if their tick rate dropped at all.
     *
     * <p>Players are obviously excluded (they're the camera). Projectiles
     * are short-lived and need exact-tick physics. Lightning, TNT,
     * fireworks, end crystals are short-lived effect entities — throttling
     * them would visibly stutter explosions and animations.
     *
     * <p>EnderDragonPart is the dragon's body segments — they're a
     * separate Entity from the EnderDragon itself, and tick-throttling
     * them while leaving the dragon ticking creates visual desync.
     */
    public static boolean isAlwaysExcludedClass(Entity entity) {
        // Note: EnderDragonPart is intentionally not listed — its tick is
        // driven from EnderDragon.tick(), so excluding EnderDragon
        // (via BossDetection.isBoss) implicitly protects all parts.
        // The 1.21.1 mapping for EnderDragonPart isn't a stable public
        // class anyway, which would make the import fragile across
        // minor version updates.
        return entity instanceof Player
            || entity instanceof ThrowableProjectile
            || entity instanceof Fireball
            || entity instanceof EyeOfEnder
            || entity instanceof ThrownTrident
            || entity instanceof FireworkRocketEntity
            || entity instanceof PrimedTnt
            || entity instanceof LightningBolt;
    }

    /**
     * Returns true if the entity is in a transient state where throttling
     * would visibly affect gameplay even if it's an ordinary mob far from
     * any player. The caller skips the throttle decision in that case.
     */
    public static boolean isInCriticalState(Entity entity) {
        // Newborn entities: don't touch until they've settled. Saves us
        // from throttling spawner output before it's even moved.
        if (entity.tickCount < NEW_ENTITY_TICK_FLOOR) {
            return true;
        }

        // Portal travel — throttling mid-portal corrupts the teleport.
        if (entity.isOnPortalCooldown()) {
            return true;
        }

        // Player-leashed entity must follow the player smoothly.
        if (entity instanceof Leashable leashable
            && leashable.getLeashData() != null
            && leashable.getLeashData().leashHolder instanceof Player) {
            return true;
        }

        // LivingEntity states: hit-stun (hurtTime), active potion effects,
        // jumping, climbing, fire — all visible to the player if interrupted.
        if (entity instanceof LivingEntity living) {
            if (living.hurtTime > 0) {
                return true;
            }
            if (living.getRemainingFireTicks() > 0) {
                return true;
            }
            if (living.onClimbable()) {
                return true;
            }
            // Note: living.jumping is package-private in some mappings;
            // we use isFallFlying() as a proxy for "in motion vertically"
            // since that's the common case throttling would visibly break.
            if (living.isFallFlying()) {
                return true;
            }
            if (!living.getActiveEffects().isEmpty()) {
                // Potion effect tick must run on schedule — throttling
                // means slow effect ticking instead of N seconds, which
                // breaks PvP balance.
                return true;
            }

            // Mob-specific transient states.
            if (living instanceof Mob mob) {
                // Combat: an aggro'd mob must respond in real time.
                if (mob.getTarget() != null) {
                    return true;
                }
                // Pathfinding in progress — WMB's "requireNoPath" gate.
                // A mob actively navigating shouldn't have its tick
                // interval expanded, or the path will visibly stutter.
                if (mob.getNavigation().isInProgress()) {
                    return true;
                }
                // Active motion (WMB's "requireLowMotion" inverted).
                // A mob with significant velocity is jumping, falling,
                // being knocked back, or running — none of which
                // tolerate skipped ticks gracefully.
                if (mob.getDeltaMovement().lengthSqr() > MOB_MOTION_THRESHOLD_SQ) {
                    return true;
                }
                // Creeper with lit fuse — the explosion timer is the
                // entire identity of this entity right now.
                if (mob instanceof Creeper creeper && creeper.isIgnited()) {
                    return true;
                }
                // Baby / love-mode animals — short critical windows.
                if (mob instanceof Animal animal && (animal.isBaby() || animal.isInLove())) {
                    return true;
                }
            }
        }

        // Moving item entities / XP orbs — throttling makes them fail
        // pickup-range checks and players "miss" drops they should have
        // collected.
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            Vec3 motion = entity.getDeltaMovement();
            if (Math.abs(motion.x) > MOVING_THRESHOLD
                || Math.abs(motion.z) > MOVING_THRESHOLD
                || motion.y > MOVING_THRESHOLD) {
                return true;
            }
        }

        return false;
    }

    /**
     * One-shot combined gate — true if the entity should be left alone
     * for any reason at all. Used by callers that don't care about
     * which specific rule triggered.
     */
    public static boolean shouldSkipThrottle(Entity entity) {
        return isAlwaysExcludedClass(entity) || isInCriticalState(entity);
    }
}
