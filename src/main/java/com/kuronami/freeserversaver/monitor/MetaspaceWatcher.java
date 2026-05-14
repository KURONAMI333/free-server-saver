package com.kuronami.freeserversaver.monitor;

import com.kuronami.freeserversaver.FreeServerSaver;
import com.kuronami.freeserversaver.config.FreeServerSaverConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tracks JVM Metaspace usage and warns when it approaches the configured
 * cap. Metaspace is where the JVM stores class metadata — every loaded
 * mod class contributes. On free tier, large modpacks
 * (Craftoria / ATM10 / FTB derivatives) frequently exhaust Metaspace
 * even when heap usage looks fine.
 *
 * <p>Symptoms reported on Reddit / community reports:
 * <ul>
 *   <li>"{@code java.lang.OutOfMemoryError: Metaspace}" crash</li>
 *   <li>{@code spark}'s memory profile shows "heap is empty" — because
 *       it's metadata, not heap</li>
 *   <li>RAM Boost <strong>does not help</strong>
 *       — that boost extends {@code -Xmx} (heap), not
 *       {@code -XX:MaxMetaspaceSize}</li>
 *   <li>the typical official advice: "remove mods or move to a
 *       paid host"</li>
 * </ul>
 *
 * <p>What this watcher does:
 * <ul>
 *   <li>Read the {@code MemoryPoolMXBean} for the Metaspace pool
 *       every 60 seconds</li>
 *   <li>WARN at 80% utilization (operator has time to act)</li>
 *   <li>ERROR at 95% (Metaspace OOM is imminent)</li>
 *   <li>Hysteresis: don't re-fire the same threshold until it drops</li>
 * </ul>
 *
 * <p>What this watcher does NOT do: Metaspace can't be throttled. Class
 * metadata isn't garbage-collected the way heap objects are (well —
 * it CAN be, with class unloading, but only when entire ClassLoaders
 * become unreachable, which doesn't happen during a running modded
 * server). The only fix is fewer mods or larger
 * {@code -XX:MaxMetaspaceSize}, neither of which we can adjust at
 * runtime.
 *
 * <p>This is why the warning is the entire feature. The earlier the
 * operator sees "Metaspace 90%," the more options they have:
 * <ol>
 *   <li>Remove an optional mod before next restart</li>
 *   <li>Move to a paid host with more headroom</li>
 *   <li>Switch to a smaller modpack</li>
 * </ol>
 * Without the warning, they get a hard crash and the world stops
 * loading.
 */
public class MetaspaceWatcher {

    private static final int CHECK_INTERVAL_TICKS = 20 * 60; // 60 seconds

    private static final double WARN_THRESHOLD_PCT = 80.0;
    private static final double CRITICAL_THRESHOLD_PCT = 95.0;

    private MemoryPoolMXBean metaspacePool;
    private int ticksUntilNext = CHECK_INTERVAL_TICKS;
    private double lastWarnedPct = 0.0;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // The Metaspace pool's bean name varies across JVM versions and
        // GCs, but "Metaspace" has been the consistent identifier since
        // Java 8. Find it once at startup.
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(pool.getName())) {
                metaspacePool = pool;
                break;
            }
        }
        if (metaspacePool == null) {
            // No Metaspace pool exposed — unusual but possible with some
            // custom JVMs. Disable silently rather than crashing.
            FreeServerSaver.LOGGER.info(
                "[Metaspace] No Metaspace memory pool exposed by this JVM. "
                + "Watcher disabled for this session.");
        } else {
            MemoryUsage initial = metaspacePool.getUsage();
            FreeServerSaver.LOGGER.info(
                "[Metaspace] Initial: {} MB used / {} MB max",
                initial.getUsed() / 1_048_576L,
                initial.getMax() / 1_048_576L);
        }
        ticksUntilNext = CHECK_INTERVAL_TICKS;
        lastWarnedPct = 0.0;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        metaspacePool = null;
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (metaspacePool == null) return;
        if (Boolean.FALSE.equals(FreeServerSaverConfig.ENABLE_METASPACE_WATCHER.get())) {
            return;
        }
        if (--ticksUntilNext > 0) return;
        ticksUntilNext = CHECK_INTERVAL_TICKS;

        MemoryUsage usage = metaspacePool.getUsage();
        long max = usage.getMax();
        if (max <= 0) {
            // -XX:MaxMetaspaceSize unset → unbounded; nothing to warn about.
            return;
        }
        double pct = (double) usage.getUsed() / max * 100.0;

        // Critical band — emit ERROR with explicit fix advice.
        if (pct >= CRITICAL_THRESHOLD_PCT && lastWarnedPct < CRITICAL_THRESHOLD_PCT) {
            FreeServerSaver.LOGGER.error(
                "[Metaspace] CRITICAL: {}% used ({} MB / {} MB). Server will "
                + "crash with 'OutOfMemoryError: Metaspace' soon. RAM Boost "
                + "does NOT help — it only extends heap. The fix is fewer "
                + "mods or moving to a paid host.",
                String.format("%.1f", pct),
                usage.getUsed() / 1_048_576L,
                max / 1_048_576L);
            lastWarnedPct = pct;
        } else if (pct >= WARN_THRESHOLD_PCT && lastWarnedPct < WARN_THRESHOLD_PCT) {
            FreeServerSaver.LOGGER.warn(
                "[Metaspace] {}% used ({} MB / {} MB) — approaching capacity. "
                + "free tier doesn't expose Metaspace tuning, and "
                + "RAM Boost doesn't help this. Consider trimming mods "
                + "before the next OOM crash.",
                String.format("%.1f", pct),
                usage.getUsed() / 1_048_576L,
                max / 1_048_576L);
            lastWarnedPct = pct;
        } else if (pct < WARN_THRESHOLD_PCT && lastWarnedPct >= WARN_THRESHOLD_PCT) {
            // Dropped back below — reset the ratchet so a future increase
            // can warn again. (Metaspace rarely drops in practice, but
            // it's safe to support the case.)
            FreeServerSaver.LOGGER.info(
                "[Metaspace] Now {}% — below warning threshold.",
                String.format("%.1f", pct));
            lastWarnedPct = 0.0;
        }
    }

    /** Used by the /env command to surface Metaspace status alongside heap. */
    public double currentPercent() {
        if (metaspacePool == null) return -1.0;
        MemoryUsage u = metaspacePool.getUsage();
        long max = u.getMax();
        if (max <= 0) return -1.0;
        return (double) u.getUsed() / max * 100.0;
    }
}
