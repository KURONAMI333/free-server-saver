package com.kuronami.freeserversaver.util;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * Centralized "is this entity special enough to never touch?" checks.
 *
 * <p>Pulled out of {@code DespawnModule} into its own utility because the
 * same check is needed by both {@code SpawnThrottleModule} (don't cancel
 * a boss spawn) and {@code EntityTickThrottleModule} (don't throttle a
 * boss's AI). Putting it in one place keeps the exemption rules in
 * lockstep — adding a new boss class only edits this file.
 *
 * <p>Three categories, each used differently by callers:
 * <ul>
 *   <li>{@link #isBoss} — vanilla bosses + max-health heuristic for modded.
 *       Always exempt from every intervention.</li>
 *   <li>{@link #isImportantNpc} — villagers, traders, golems. Don't
 *       despawn (gameplay breakage) but tick-throttling them at far
 *       distance is fine.</li>
 *   <li>{@link #isPlayerProtected} — named, leashed, tamed, persistence
 *       flag set. The player explicitly signaled care for this entity.</li>
 * </ul>
 */
public final class BossDetection {

    /**
     * Health threshold above which we treat a mob as "boss-shaped."
     * Vanilla normal mobs cap around 40 HP (Iron Golem); 80 picks up
     * modded bosses without false-positiving on Pillagers or Vindicators
     * (who are around 24 HP).
     */
    private static final float BOSS_HP_HEURISTIC = 80.0f;

    private BossDetection() {}

    /**
     * True for any entity that should never be despawned, never have its
     * spawn cancelled, and never have its AI throttled.
     *
     * <p>Detection layers (any one is enough to be a boss):
     * <ol>
     *   <li>Explicit vanilla boss class — bulletproof for vanilla.</li>
     *   <li>{@link Mob#getMaxHealth()} above {@link #BOSS_HP_HEURISTIC}
     *       — catches modded bosses that we can't enumerate.</li>
     *   <li>Vanilla raid leaders are tagged via {@code raid_omen} effect
     *       but those aren't bosses in the gameplay sense, so we don't
     *       check that here. If they need exemption, name-tag them.</li>
     * </ol>
     */
    public static boolean isBoss(Mob mob) {
        if (mob instanceof EnderDragon
            || mob instanceof WitherBoss
            || mob instanceof Warden
            || mob instanceof ElderGuardian
            || mob instanceof Ravager
            || mob instanceof Hoglin) {
            return true;
        }
        // Modded-boss heuristic. getMaxHealth() reads the entity's
        // current cached attribute, which is set at finalizeSpawn —
        // safe to call on any Mob that's already in a level.
        return mob.getMaxHealth() > BOSS_HP_HEURISTIC;
    }

    /**
     * True for entities whose despawn would visibly break gameplay even
     * though they aren't bosses. Villagers maintain workstation bindings;
     * golems guard iron farms; Allays are quest-like helpers.
     *
     * <p>Note: this is despawn-only. Throttling the AI of a far-away
     * villager is fine — they'll resume normal behavior the moment a
     * player walks near.
     */
    public static boolean isImportantNpc(Mob mob) {
        return mob instanceof Villager
            || mob instanceof WanderingTrader
            || mob instanceof IronGolem
            || mob instanceof SnowGolem
            || mob instanceof Allay;
    }

    /**
     * True when the player has explicitly signaled that they care about
     * this entity — even if it's a generic zombie. Name tags, leads,
     * taming, and persistence flags are all forms of "don't touch this."
     */
    public static boolean isPlayerProtected(Mob mob) {
        if (mob.hasCustomName()) {
            return true;
        }
        if (mob.isLeashed()) {
            return true;
        }
        if (mob.isPersistenceRequired()) {
            return true;
        }
        // TamableAnimal#isTame is the standard test for "this is someone's
        // pet." We import via instanceof to avoid casts inside the call.
        if (mob instanceof net.minecraft.world.entity.TamableAnimal tame && tame.isTame()) {
            return true;
        }
        return false;
    }

    /**
     * The strongest "do not touch" combinator — true if any of the three
     * categories trigger. Most modules want this and don't care which
     * specific category matched.
     */
    public static boolean shouldNeverTouch(Mob mob) {
        return isBoss(mob) || isImportantNpc(mob) || isPlayerProtected(mob);
    }
}
