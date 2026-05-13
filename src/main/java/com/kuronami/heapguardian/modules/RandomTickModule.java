package com.kuronami.heapguardian.modules;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.config.HeapGuardianConfig;
import com.kuronami.heapguardian.monitor.ThrottleLevel;
import com.kuronami.heapguardian.monitor.ThrottleLevelChangedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Adjusts the {@code randomTickSpeed} gamerule based on heap pressure.
 *
 * <p>Random ticks are the engine behind crop growth, leaf decay, fire
 * spread, snow accumulation, and similar passive world updates. Each
 * random tick allocates and updates block-state objects, so on a heap
 * under pressure they're free wins to throttle: nobody notices wheat
 * growing 30% slower for two minutes, but everyone notices the 500ms
 * stutter caused by an emergency GC.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@code NORMAL} → vanilla default ({@link #DEFAULT_RANDOM_TICK_SPEED})</li>
 *   <li>{@code L1_MILD} → {@link #L1_RANDOM_TICK_SPEED}</li>
 *   <li>{@code L2_HEAVY} and above → 0 (passive world updates suspended)</li>
 * </ul>
 *
 * <p>⚠️ Pinned to Minecraft 1.21.1: the random-tick logic changed in
 * 1.21.2+ in a way that affects how gamerule writes propagate; see
 * {@code HEAP_GUARDIAN_NOTES.md} for the GitHub issue link. If we ever
 * target a wider version range, this module needs revisiting.
 */
public class RandomTickModule {

    /** Vanilla default value for {@code randomTickSpeed} since 1.18. */
    private static final int DEFAULT_RANDOM_TICK_SPEED = 3;

    /** Reduced rate applied at {@link ThrottleLevel#L1_MILD}. */
    private static final int L1_RANDOM_TICK_SPEED = 1;

    private MinecraftServer server;

    /** Cache the user's pre-throttle value so we can restore it cleanly. */
    private int savedRandomTickSpeed = DEFAULT_RANDOM_TICK_SPEED;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_RANDOM_TICK_THROTTLE.get())) {
            return;
        }
        server = event.getServer();

        // Snapshot the user's current configured value rather than assuming
        // it's the vanilla default. A user might have set randomTickSpeed
        // higher in server.properties or via a datapack; restoring to 3
        // would silently change their intended behavior.
        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            savedRandomTickSpeed = overworld.getGameRules()
                .getInt(GameRules.RULE_RANDOMTICKING);
            HeapGuardian.LOGGER.info(
                "RandomTickModule armed (current randomTickSpeed = {}).",
                savedRandomTickSpeed);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Restore on shutdown so the saved world doesn't carry our last
        // throttled value into the next session.
        if (server != null) {
            applyRandomTickSpeed(savedRandomTickSpeed);
        }
        server = null;
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (server == null) {
            return;
        }
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_RANDOM_TICK_THROTTLE.get())) {
            return;
        }

        int targetSpeed = switch (event.current()) {
            case NORMAL -> savedRandomTickSpeed;
            case L1_MILD -> L1_RANDOM_TICK_SPEED;
            case L2_HEAVY, L3_AGGRESSIVE, L4_EMERGENCY -> 0;
        };

        applyRandomTickSpeed(targetSpeed);

        if (Boolean.TRUE.equals(HeapGuardianConfig.VERBOSE_LOGGING.get())) {
            HeapGuardian.LOGGER.debug(
                "[RandomTick] {} -> randomTickSpeed={}", event.current(), targetSpeed);
        }
    }

    /**
     * Apply the gamerule change to every loaded dimension. The
     * {@code randomTickSpeed} gamerule is per-level in 1.21, so we have
     * to iterate — setting it on the overworld doesn't affect the nether
     * or modded dimensions.
     */
    private void applyRandomTickSpeed(int value) {
        for (ServerLevel level : server.getAllLevels()) {
            level.getGameRules()
                .getRule(GameRules.RULE_RANDOMTICKING)
                .set(value, server);
        }
    }
}
