package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.compat.CompatibilityCoordinator;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import com.kuronami.freeserversaver.monitor.ThrottleLevel;
import com.kuronami.freeserversaver.monitor.ThrottleLevelChangedEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Last-resort: at {@link ThrottleLevel#L4_EMERGENCY}, slow the game's
 * tick rate via the vanilla {@code /tick rate} command.
 *
 * <p>The {@code /tick rate <float>} command was added in 1.20.4 and
 * lets the server target any real-time rate from 1.0 to 10000.0 TPS.
 * At {@link #L4_TICK_RATE} (= 10.0) the game runs at half speed —
 * mobs move slower, redstone runs slower, but the server only has
 * to do half as much per second, which gives the GC room to breathe.
 *
 * <p>Why command instead of {@code MinecraftServer#tickRateManager().setTickRate()}?
 * Two reasons:
 * <ol>
 *   <li>The command goes through the same broadcasting path the
 *       vanilla command uses, so all players see the same "Tick rate
 *       changed to N.N" feedback — no special client-side mod needed.</li>
 *   <li>The Server Stasis mod (MIT, credited in NOTICE.md) showed this
 *       pattern works reliably back to 1.20.4 with zero mixins. Calling
 *       the tick rate manager method directly bypasses some of vanilla's
 *       broadcast logic.</li>
 * </ol>
 *
 * <p>This module is the most user-visible intervention — players will
 * absolutely notice 10 TPS. Logged at WARN level (not DEBUG) so the
 * server operator can correlate "the game went slow" with "heap was at
 * 85%". Players who notice can run {@code /freeserversaver status} to see
 * why.
 */
public class TickRateModule {

    /** TPS during emergency throttling. 10.0 = half speed. */
    private static final float L4_TICK_RATE = 10.0f;

    /** Vanilla default — what we restore to on recovery. */
    private static final float DEFAULT_TICK_RATE = 20.0f;

    private MinecraftServer server;
    private boolean throttled = false;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        throttled = false;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Restore on shutdown so the next session starts at 20 TPS
        // regardless of what state we left it in.
        if (server != null && throttled) {
            applyTickRate(DEFAULT_TICK_RATE);
        }
        server = null;
        throttled = false;
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (server == null) {
            return;
        }
        // /tick rate is a single global setting — two mods writing to it
        // race each other. Yield to anything else that manages it.
        if (CompatibilityCoordinator.yieldTickRate()) {
            return;
        }
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_TICK_RATE_THROTTLE.get())) {
            // If we'd previously throttled but the user just turned the
            // module off, restore. Otherwise the server stays at 10 TPS
            // until the next throttle event passes the gate.
            if (throttled) {
                applyTickRate(DEFAULT_TICK_RATE);
                throttled = false;
            }
            return;
        }

        if (event.current() == ThrottleLevel.L4_EMERGENCY && !throttled) {
            applyTickRate(L4_TICK_RATE);
            throttled = true;
            HeapGuardian.LOGGER.warn(
                "[TickRate] EMERGENCY — server slowed to {} TPS to relieve heap pressure.",
                L4_TICK_RATE);
        } else if (event.current() != ThrottleLevel.L4_EMERGENCY && throttled) {
            applyTickRate(DEFAULT_TICK_RATE);
            throttled = false;
            HeapGuardian.LOGGER.info("[TickRate] Recovery — restored to {} TPS.", DEFAULT_TICK_RATE);
        }
    }

    /**
     * Invoke {@code /tick rate <rate>} as the server console. We need
     * permission level 4 (server console / op) so the vanilla command
     * parser accepts the call.
     */
    private void applyTickRate(float rate) {
        CommandSourceStack source = server.createCommandSourceStack()
            .withPermission(4)
            .withSuppressedOutput(); // don't spam the console feedback
        server.getCommands().performPrefixedCommand(
            source,
            "tick rate " + rate);

        if (Boolean.TRUE.equals(HeapGuardianConfig.VERBOSE_LOGGING.get())) {
            HeapGuardian.LOGGER.debug("[TickRate] /tick rate {}", rate);
        }
    }
}
