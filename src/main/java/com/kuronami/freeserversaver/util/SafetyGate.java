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

/**
 * Answers one question for {@code EntityTickThrottleModule}: may this
 * entity's tick be stretched right now, or would that be visible to a
 * player?
 *
 * <p>Distinct from {@link BossDetection}, which answers "what KIND of
 * entity is this." BossDetection is about identity and is stable for the
 * lifetime of the entity; SafetyGate is about the current tick and its
 * answer changes constantly. A regular zombie that's mid-swing at a
 * player must keep ticking even though it isn't a boss.
 *
 * <h3>The four reasons to leave an entity alone</h3>
 * <ol>
 *   <li><strong>Real-time physics.</strong> Projectiles and effect
 *       entities compute their trajectory from per-tick deltas. Skip a
 *       tick and the arc is wrong, not just late.</li>
 *   <li><strong>In motion.</strong> Anything with meaningful velocity is
 *       mid-fall, mid-knockback or mid-flight. Position error is
 *       proportional to the interval we skip.</li>
 *   <li><strong>Timed state.</strong> Fuses, potion durations, hit-stun
 *       and fire ticks are countdowns the player can see. Stretching the
 *       tick stretches the countdown.</li>
 *   <li><strong>Player intent.</strong> The player leashed it, or their
 *       presence is what the entity is reacting to.</li>
 * </ol>
 *
 * <p>Everything here runs once per entity per tick, so each check is a
 * field read or an {@code instanceof}. Nothing allocates and nothing
 * queries the level.
 */
public final class SafetyGate {

    /**
     * Squared speed above which an entity counts as "in motion", in
     * blocks per tick. 0.005 b/t is 0.1 blocks per second — an entity
     * slower than this cannot leave its current block inside any throttle
     * interval this mod applies, so stretching its tick is invisible.
     *
     * <p>One threshold covers mobs, dropped items and XP orbs. They fail
     * in the same way (drift, missed pickup radius) and there is no
     * reason for the cutoff to differ between them.
     */
    private static final double MOTION_THRESHOLD_SQ = 0.005 * 0.005;

    /**
     * Ticks of grace after an entity enters the world. Five
     * starvation-floor windows (the floor is 20 ticks) — long enough for
     * a mob to finish its first pathfind and settle on a goal, short
     * enough that a runaway spawner is under control within five seconds
     * rather than ten.
     */
    private static final int SPAWN_GRACE_TICKS = 100;

    private SafetyGate() {}

    /**
     * True when this entity must run a full tick. The caller skips its
     * throttle decision entirely and lets vanilla tick the entity.
     *
     * <p>Ordered cheapest-first: an int compare, then two
     * {@code instanceof} groups, then the {@link LivingEntity} branch
     * that most entities in a loaded chunk never reach.
     */
    public static boolean shouldSkipThrottle(Entity entity) {
        return entity.tickCount < SPAWN_GRACE_TICKS
            || entity.isOnPortalCooldown()
            || needsRealTimePhysics(entity)
            || isMoving(entity)
            || isHeldByPlayer(entity)
            || hasLivingStateWorthProtecting(entity);
    }

    /**
     * Entity classes whose whole behavior is a per-tick trajectory or a
     * short-lived animation. Players lead the chain because the check is
     * free and they are the most common entity that must never be
     * touched.
     */
    private static boolean needsRealTimePhysics(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        // Ballistic: position is integrated per tick, so a skipped tick
        // changes where it lands rather than when.
        if (entity instanceof ThrowableProjectile
            || entity instanceof Fireball
            || entity instanceof ThrownTrident
            || entity instanceof EyeOfEnder) {
            return true;
        }
        // Short-lived effects: the entity exists for a countdown and then
        // is gone. Stretching the countdown stretches the explosion.
        return entity instanceof PrimedTnt
            || entity instanceof FireworkRocketEntity
            || entity instanceof LightningBolt;
    }

    /**
     * Velocity test, applied to the entity kinds where drift is visible:
     * mobs (they walk, fall and take knockback) and the two pickup types
     * (a throttled item drifts past the player's collection radius and
     * reads as a lost drop).
     *
     * <p>Deliberately not applied to every entity — most non-living
     * entities that move are already covered by
     * {@link #needsRealTimePhysics}, and the ones that aren't (boats,
     * minecarts) are player-driven and rare enough not to matter.
     */
    private static boolean isMoving(Entity entity) {
        if (entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return entity.getDeltaMovement().lengthSqr() > MOTION_THRESHOLD_SQ;
        }
        return false;
    }

    /**
     * A leashed entity is pulled by the player's own movement, so it has
     * to resolve its position on the player's schedule, not ours. Only a
     * player holder counts — fence-post leashes have no such constraint.
     */
    private static boolean isHeldByPlayer(Entity entity) {
        if (!(entity instanceof Leashable leashable)) {
            return false;
        }
        Leashable.LeashData leash = leashable.getLeashData();
        return leash != null && leash.leashHolder instanceof Player;
    }

    /**
     * States that only exist on {@link LivingEntity} and, below that,
     * only on {@link Mob}. Split out so the common case (an item lying on
     * the ground, an armour stand) never pays for these reads.
     */
    private static boolean hasLivingStateWorthProtecting(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }

        // Countdowns the player watches tick down. Stretching our tick
        // stretches the effect duration, which changes PvP outcomes.
        if (living.hurtTime > 0
            || living.getRemainingFireTicks() > 0
            || !living.getActiveEffects().isEmpty()) {
            return true;
        }

        // Vertical situations where a stale position is visible as
        // clipping through the ladder or stalling in the air. (Vanilla's
        // `jumping` flag is package-private, so elytra flight stands in
        // for the powered case.)
        if (living.onClimbable() || living.isFallFlying()) {
            return true;
        }

        if (!(living instanceof Mob mob)) {
            return false;
        }

        // A mob that has locked on, or is walking a path, is the case
        // players actually notice: throttling turns a chase into a
        // stutter. Both are single field/flag reads.
        if (mob.getTarget() != null || mob.getNavigation().isInProgress()) {
            return true;
        }

        // Breeding and growth are short windows the player is usually
        // standing right next to. Animals and monsters are disjoint, so
        // this branch also skips the fuse check below for free.
        if (mob instanceof Animal animal) {
            return animal.isBaby() || animal.isInLove();
        }

        // A lit creeper is nothing but its fuse.
        return mob instanceof Creeper creeper && creeper.isIgnited();
    }
}
