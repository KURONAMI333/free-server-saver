package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Watches the world directory size against Aternos's 4GB ceiling.
 *
 * <p>Once every {@link #SCAN_INTERVAL_TICKS} (30 minutes), walks the
 * server's world directory and sums file sizes. If the total crosses
 * {@link #WARN_THRESHOLD_BYTES} (3.5 GB), emits a WARN log line and
 * (if enabled) a Discord notification — Aternos will stop the server
 * once size exceeds 4 GB, so the user has roughly one play session of
 * lead time once they hit 3.5.
 *
 * <p>The scan is intentionally on the main thread (inside a tick handler)
 * because filesystem walks of 4GB take maybe 200ms on Aternos-grade
 * storage and we trigger them every 30 minutes — that's a tolerable
 * spike vs the complexity of off-thread execution. The 30-minute
 * interval is generous enough that the cost averages out.
 *
 * <p>This module does NOT delete anything. It's a warning system. The
 * {@code /freeserversaver prune} command (via {@link ChunkPruningModule})
 * is what the operator runs to actually reduce loaded chunks; on-disk
 * region-file deletion stays out of scope until a separate phase that
 * can afford the safety surface (player builds must not be deleted).
 */
public class StorageMonitor {

    /** Run a scan every N ticks. 30 minutes. */
    private static final int SCAN_INTERVAL_TICKS = 20 * 60 * 30;

    /** Warn when world directory exceeds this size. 3.5 GB. */
    private static final long WARN_THRESHOLD_BYTES = 3_500L * 1024L * 1024L;

    /** Hard-warn threshold: world over 4 GB = Aternos will refuse to start. */
    private static final long CRITICAL_THRESHOLD_BYTES = 4_000L * 1024L * 1024L;

    private MinecraftServer server;
    private int ticksUntilNextScan = SCAN_INTERVAL_TICKS;
    private long lastWarnedSize = 0;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        ticksUntilNextScan = SCAN_INTERVAL_TICKS;
        // Run an initial scan a few seconds after startup. Catches the
        // "you've already exceeded the cap" case immediately rather than
        // making the user wait 30 minutes for the first warning.
        ticksUntilNextScan = 20 * 30; // 30 seconds
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (server == null) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_STORAGE_MONITOR.get())) {
            return;
        }
        if (--ticksUntilNextScan > 0) return;
        ticksUntilNextScan = SCAN_INTERVAL_TICKS;

        runScan();
    }

    /**
     * Public entry for on-demand scanning. Used by a future
     * {@code /freeserversaver storage} command and on server start.
     * Returns the directory size in bytes, or -1 on error.
     */
    public long runScan() {
        if (server == null) return -1L;
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        try {
            long size = computeSize(worldDir);
            evaluate(size);
            return size;
        } catch (IOException e) {
            HeapGuardian.LOGGER.warn(
                "[StorageMonitor] Could not walk world directory: {}", e.getMessage());
            return -1L;
        }
    }

    private long computeSize(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException ignored) {
                        return 0L;
                    }
                })
                .sum();
        }
    }

    private void evaluate(long size) {
        // Only emit if we've crossed a new threshold since the last warn —
        // re-emitting the same warning every 30 minutes when the user
        // hasn't taken action is just noise.
        if (size >= CRITICAL_THRESHOLD_BYTES && lastWarnedSize < CRITICAL_THRESHOLD_BYTES) {
            HeapGuardian.LOGGER.error(
                "[StorageMonitor] World directory is {} MB — over Aternos's 4GB cap. "
                + "The server may refuse to start on the next boot. "
                + "Run /freeserversaver prune to reduce loaded chunks, or trim "
                + "unused dimensions via Aternos's world manager.",
                size / 1_048_576L);
            lastWarnedSize = size;
        } else if (size >= WARN_THRESHOLD_BYTES && lastWarnedSize < WARN_THRESHOLD_BYTES) {
            HeapGuardian.LOGGER.warn(
                "[StorageMonitor] World directory is {} MB — approaching Aternos's "
                + "4GB cap. Consider /freeserversaver prune to reduce loaded chunks.",
                size / 1_048_576L);
            lastWarnedSize = size;
        } else if (size < WARN_THRESHOLD_BYTES && lastWarnedSize >= WARN_THRESHOLD_BYTES) {
            // User shrank the world or pruned. Reset the warn ratchet so
            // future warnings can fire again if they re-grow.
            HeapGuardian.LOGGER.info(
                "[StorageMonitor] World directory now {} MB — below warning threshold.",
                size / 1_048_576L);
            lastWarnedSize = 0;
        }
    }
}
