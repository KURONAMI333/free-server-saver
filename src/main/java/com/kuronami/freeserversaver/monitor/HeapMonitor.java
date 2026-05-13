package com.kuronami.freeserversaver.monitor;

import com.kuronami.freeserversaver.HeapGuardian;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Heap polling + tier classification.
 *
 * <p>Polls {@link MemoryMXBean#getHeapMemoryUsage()} once every
 * {@link #POLL_INTERVAL_TICKS} server ticks (~2 s by default). When the
 * derived {@link ThrottleLevel} changes, fires a
 * {@link ThrottleLevelChangedEvent} on the NeoForge event bus so each
 * intervention module ({@code RandomTickModule}, {@code SpawnThrottleModule},
 * ...) can adjust independently.
 *
 * <p>Hysteresis on the falling edge: a level is only released when the
 * heap drops more than {@link ThrottleLevel#HYSTERESIS_MARGIN} below the
 * current level's entry threshold. This prevents oscillation when heap
 * occupancy sits on a boundary.
 *
 * <p>Startup delay: we ignore the first {@link #STARTUP_DELAY_TICKS}
 * ticks (~20 s) after {@link ServerStartedEvent}. Mod registration, chunk
 * pre-generation, and world load all allocate large blocks of memory that
 * don't reflect steady-state usage; throttling against those numbers
 * would produce a false alarm before any player has even joined.
 *
 * <p>Implementation note: keeping a single shared {@link MemoryMXBean}
 * field rather than re-fetching it each tick. {@code ManagementFactory}
 * caches the bean internally, but the field makes the intent explicit
 * and avoids the lookup cost on the hot tick path.
 */
public class HeapMonitor {

    /** Ticks between heap polls (40 ticks = 2 seconds at 20 TPS). */
    private static final int POLL_INTERVAL_TICKS = 40;

    /** Ignore measurements for this many ticks after {@link ServerStartedEvent}. */
    private static final int STARTUP_DELAY_TICKS = 20 * 20; // 20 seconds

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private boolean serverStarted = false;
    private int ticksSinceStart = 0;
    private int ticksSincePoll = 0;
    private ThrottleLevel currentLevel = ThrottleLevel.NORMAL;
    private double lastHeapPercent = 0.0;

    /**
     * Optional dynamic shift to all tier thresholds. AutoTuner sets this
     * to a value in [-10, +10]; a value of +5 means "treat the L1 entry
     * threshold (60%) as if it were 65%." Read on every poll, so changes
     * take effect within 2 seconds of being set.
     *
     * <p>Volatile because AutoTuner runs on the server tick thread and
     * HeapMonitor's poll() can run on different ticks; the cheapest
     * coordination is the JMM volatile ordering guarantee.
     */
    private volatile double thresholdOffset = 0.0;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Reset counters: a server-stop / server-start cycle (e.g. in dev
        // runClient) should start the warm-up period fresh, otherwise the
        // monitor would react to startup allocations as if they were the
        // result of normal gameplay.
        serverStarted = true;
        ticksSinceStart = 0;
        ticksSincePoll = 0;
        currentLevel = ThrottleLevel.NORMAL;
        HeapGuardian.LOGGER.info(
            "Heap Guardian armed — {}-tick warm-up, then poll every {} ticks.",
            STARTUP_DELAY_TICKS, POLL_INTERVAL_TICKS);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        serverStarted = false;
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (!serverStarted) {
            return;
        }

        ticksSinceStart++;
        if (ticksSinceStart < STARTUP_DELAY_TICKS) {
            return;
        }

        ticksSincePoll++;
        if (ticksSincePoll < POLL_INTERVAL_TICKS) {
            return;
        }
        ticksSincePoll = 0;

        poll();
    }

    /**
     * Read the heap and update the current throttle level if the
     * classification has shifted (rising or falling, with hysteresis
     * applied on the falling edge).
     */
    private void poll() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax();
        if (max <= 0) {
            // Some JVMs report -1 for getMax() when no -Xmx is set. There's
            // nothing meaningful to compare against, so skip silently.
            return;
        }

        double pct = (double) used / max * 100.0;
        lastHeapPercent = pct;

        // Apply AutoTuner offset to the percentage we classify against.
        // Subtracting the offset is equivalent to raising every threshold
        // by the same amount — pct=70%, offset=+5 means "treat as 65%,"
        // which keeps the mob at one tier lower than the static thresholds
        // would have placed it.
        double offset = thresholdOffset;
        double effectivePct = pct - offset;
        ThrottleLevel rising = ThrottleLevel.forHeapPercent(effectivePct);

        // Falling-edge check: only release the current level if we've dropped
        // strictly below its (also offset-adjusted) entry threshold by the
        // hysteresis margin.
        ThrottleLevel newLevel = currentLevel;
        if (rising.ordinal() > currentLevel.ordinal()) {
            newLevel = rising;
        } else if (rising.ordinal() < currentLevel.ordinal()) {
            double releaseAt = currentLevel.enterAt() - ThrottleLevel.HYSTERESIS_MARGIN;
            if (effectivePct < releaseAt) {
                newLevel = rising;
            }
        }

        if (newLevel != currentLevel) {
            ThrottleLevel previous = currentLevel;
            currentLevel = newLevel;

            // SLF4J's {} placeholder doesn't support format specifiers, so
            // format the percentage separately before logging.
            HeapGuardian.LOGGER.info(
                "Throttle level: {} -> {} (heap {}%, used={}MB / max={}MB)",
                previous, newLevel,
                String.format("%.1f", pct),
                used / 1_048_576L,
                max / 1_048_576L);

            NeoForge.EVENT_BUS.post(
                new ThrottleLevelChangedEvent(previous, newLevel, pct, used, max));
        }
    }

    /** Current tier — exposed for the status command and other modules. */
    public ThrottleLevel currentLevel() {
        return currentLevel;
    }

    /** Last polled heap percentage — exposed for the status command. */
    public double lastHeapPercent() {
        return lastHeapPercent;
    }

    /**
     * Set the AutoTuner-controlled offset applied to all tier thresholds.
     * Plus = throttle later, minus = throttle earlier. Volatile write —
     * the next poll() in this tick or the next will pick it up.
     */
    public void setThresholdOffset(double offset) {
        this.thresholdOffset = offset;
    }
}
