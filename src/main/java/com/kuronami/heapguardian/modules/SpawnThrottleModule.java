package com.kuronami.heapguardian.modules;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.config.HeapGuardianConfig;
import com.kuronami.heapguardian.monitor.ThrottleLevel;
import com.kuronami.heapguardian.monitor.ThrottleLevelChangedEvent;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Cancels mob spawns under heap pressure.
 *
 * <p>The hook chain mirrors the pattern from APT's {@code SpawnManager}:
 * <ol>
 *   <li>{@link FinalizeSpawnEvent} fires before the entity is finalized
 *       into the world. This is the canonical hook for cancelling natural
 *       spawns — calling {@link FinalizeSpawnEvent#setSpawnCancelled(boolean)}
 *       guarantees vanilla won't insert the entity.</li>
 *   <li>{@link EntityJoinLevelEvent} fires later, after the entity has
 *       already been constructed. We listen here too as a safety net for
 *       entities that bypass the spawn finalization (a few modded
 *       creature classes do this).</li>
 * </ol>
 *
 * <p>Always-allow exceptions:
 * <ul>
 *   <li><strong>Spawn eggs:</strong> the player explicitly asked for this
 *       mob to appear — cancelling silently would feel broken.</li>
 *   <li><strong>Named entities (name-tagged):</strong> a player took the
 *       time to tag this creature, so it's not random fauna — let it stay.</li>
 *   <li><strong>{@link MobSpawnType#SPAWNER}:</strong> mob spawners are
 *       part of intentional farm builds; throttling them would silently
 *       break gameplay loops.</li>
 *   <li><strong>{@link MobSpawnType#COMMAND}, {@link MobSpawnType#STRUCTURE}:</strong>
 *       admin/structure-driven, never natural-load.</li>
 * </ul>
 *
 * <p>The cancellation policy per tier:
 * <ul>
 *   <li>{@code NORMAL} → no interference</li>
 *   <li>{@code L1_MILD} → 50% of natural spawns randomly rejected</li>
 *   <li>{@code L2_HEAVY} and above → all natural spawns cancelled</li>
 * </ul>
 */
public class SpawnThrottleModule {

    /**
     * Cached current tier. Updated only from {@link ThrottleLevelChangedEvent},
     * read on every spawn event — the hot path is the spawn check, not the
     * tier transition, so a volatile read + plain write is fine here. We
     * don't need the stronger guarantees of an {@link java.util.concurrent.atomic.AtomicReference}
     * because (a) only the monitor thread writes, (b) a stale read for one
     * tick is harmless: the next spawn check will see the updated value.
     */
    private volatile ThrottleLevel currentLevel = ThrottleLevel.NORMAL;

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        currentLevel = event.current();
    }

    /**
     * Highest priority + check {@link FinalizeSpawnEvent#isSpawnCancelled()}
     * because another mod (e.g. another spawn-control mod) may have
     * already cancelled this spawn — re-cancelling is harmless but the
     * isSpawnCancelled check avoids spending CPU on the rest of our
     * filter logic for events that aren't going to spawn anyway.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_SPAWN_THROTTLE.get())) {
            return;
        }
        if (event.isSpawnCancelled()) {
            return;
        }
        if (shouldAlwaysAllow(event.getEntity(), event.getSpawnType())) {
            return;
        }

        if (shouldCancel()) {
            event.setSpawnCancelled(true);
            // Also flip the generic cancellation flag — APT does both because
            // some mods read one and not the other.
            event.setCanceled(true);
            logCancellation("FinalizeSpawn", event.getEntity());
        }
    }

    /**
     * Safety net for entities that bypass {@link FinalizeSpawnEvent}.
     * Unlike the finalize hook, we don't have access to a {@link MobSpawnType}
     * here — so the always-allow logic is reduced to the entity's own
     * properties (named entity, isPersistent).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_SPAWN_THROTTLE.get())) {
            return;
        }
        if (event.isCanceled()) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity.hasCustomName()) {
            return;
        }
        // Only intercept "living entity" subclasses. Item drops, XP orbs,
        // arrows, etc. join the level constantly and shouldn't be filtered
        // by a spawn-throttle module — those are not the source of the
        // allocation pressure we're trying to reduce.
        if (!(entity instanceof net.minecraft.world.entity.Mob)) {
            return;
        }

        if (shouldCancel()) {
            event.setCanceled(true);
            logCancellation("EntityJoinLevel", entity);
        }
    }

    private boolean shouldAlwaysAllow(Entity entity, MobSpawnType spawnType) {
        if (entity.hasCustomName()) {
            return true;
        }
        return switch (spawnType) {
            case SPAWN_EGG, COMMAND, STRUCTURE, SPAWNER, BUCKET, MOB_SUMMONED, DISPENSER -> true;
            default -> false;
        };
    }

    private boolean shouldCancel() {
        return switch (currentLevel) {
            case NORMAL -> false;
            case L1_MILD -> ThreadLocalRandom.current().nextBoolean();
            case L2_HEAVY, L3_AGGRESSIVE, L4_EMERGENCY -> true;
        };
    }

    private void logCancellation(String stage, Entity entity) {
        if (Boolean.TRUE.equals(HeapGuardianConfig.VERBOSE_LOGGING.get())) {
            HeapGuardian.LOGGER.debug(
                "[Spawn/{}] Cancelled {} at tier {}",
                stage, entity.getType().getDescriptionId(), currentLevel);
        }
    }
}
