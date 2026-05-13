package com.kuronami.freeserversaver.monitor;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Records the time from JVM start to {@code ServerStartedEvent} and
 * keeps a small on-disk history so it can warn when the average
 * approaches Aternos's 10-minute startup limit.
 *
 * <p>Aternos's hard rule: if a server takes more than 600 seconds to
 * reach the "started" state, it's killed. Players have to remove mods,
 * which is the worst possible UX — your modpack is too big AFTER you've
 * already built a world on it. The only escape is "remove mods" or
 * "switch to a smaller pack." Heap Guardian can't speed up mod load
 * (that's ModernFix's job), but it CAN give the user an early warning
 * before they hit the wall.
 *
 * <p>Approach:
 * <ul>
 *   <li>Record JVM uptime at {@link ServerStartedEvent}. That's the
 *       elapsed time from java process start to "ready for players."</li>
 *   <li>Append it to a flat-file history at
 *       {@code <world>/freeserversaver-boottimes.txt}. The file holds
 *       the last {@link #HISTORY_LIMIT} entries; older lines are
 *       trimmed.</li>
 *   <li>If the most recent 3 entries average above {@link #WARN_THRESHOLD_MS}
 *       (8 minutes), log a WARN at next startup with a recommendation.</li>
 * </ul>
 *
 * <p>Why a flat file instead of HG's existing config? The config is
 * a TOML schema with typed entries; a list of timestamps doesn't fit
 * cleanly. Plain text is one append per boot, no parser to maintain.
 *
 * <p>Why warn at 8 minutes instead of 10? Aternos's hard kill is at
 * 600 seconds. A warning at 480 seconds gives the user one or two more
 * boots of margin before the next one fails — enough time to install
 * ModernFix or trim their modpack.
 *
 * <p>This module has no competitor and does not yield. It's purely
 * observational and never interferes with mod loading.
 */
public class BootTimeTracker {

    private static final int HISTORY_LIMIT = 10;
    private static final long WARN_THRESHOLD_MS = 8L * 60L * 1000L; // 8 min
    private static final int RECENT_AVG_WINDOW = 3;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final String HISTORY_FILE_NAME = "freeserversaver-boottimes.txt";

    /**
     * JVM start time, captured at mod constructor (see HeapGuardian).
     * Compared with ServerStartedEvent fire-time to get boot duration.
     */
    private static volatile long modConstructedAtMs = 0L;

    public static void recordModConstructed() {
        // The JVM's own start time would be a slight underestimate
        // (we'd miss the time before the JVM started counting), but
        // {@code ManagementFactory.getRuntimeMXBean().getUptime()} at
        // ServerStartedEvent gives us elapsed time since process start —
        // which is the real number we want.
        modConstructedAtMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_BOOT_TIME_TRACKER.get())) {
            return;
        }
        MinecraftServer server = event.getServer();

        // Read total JVM uptime — this includes EVERYTHING since the
        // java process started, which is what Aternos's 10-minute timer
        // counts.
        long jvmUptimeMs = java.lang.management.ManagementFactory
            .getRuntimeMXBean().getUptime();

        HeapGuardian.LOGGER.info(
            "[BootTime] Server reached 'started' state {} ms after JVM start ({}s, " +
            "Aternos cap is 600s).",
            jvmUptimeMs, jvmUptimeMs / 1000);

        // Persist and check trend.
        Path historyFile = server.getWorldPath(LevelResource.ROOT)
            .resolve(HISTORY_FILE_NAME);
        List<Long> history = readHistory(historyFile);
        history.add(jvmUptimeMs);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        writeHistory(historyFile, history);

        // If the recent window is approaching the cap, warn the operator.
        if (history.size() >= RECENT_AVG_WINDOW) {
            long sum = 0;
            int n = 0;
            for (int i = history.size() - RECENT_AVG_WINDOW; i < history.size(); i++) {
                sum += history.get(i);
                n++;
            }
            long avgMs = sum / n;
            if (avgMs > WARN_THRESHOLD_MS) {
                HeapGuardian.LOGGER.warn(
                    "[BootTime] Recent {}-boot average is {}s — approaching the " +
                    "600s Aternos limit. Install ModernFix (mod-loading speedup) " +
                    "or trim your modpack before the next start fails.",
                    n, avgMs / 1000);
            }
        }
    }

    /** Read prior entries from disk. Missing or malformed = empty list. */
    private List<Long> readHistory(Path file) {
        List<Long> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                // Format: "yyyy-MM-dd HH:mm:ss  <ms>"
                int sep = line.lastIndexOf("  ");
                if (sep < 0) continue;
                try {
                    out.add(Long.parseLong(line.substring(sep + 2).trim()));
                } catch (NumberFormatException ignored) {
                    // skip malformed
                }
            }
        } catch (IOException e) {
            HeapGuardian.LOGGER.warn("[BootTime] Could not read history: {}",
                e.getMessage());
        }
        return out;
    }

    /** Write the (possibly trimmed) entries back. Best-effort; failures logged not thrown. */
    private void writeHistory(Path file, List<Long> entries) {
        // Re-build with new timestamps so the file is human-readable.
        List<String> lines = new ArrayList<>();
        String now = FMT.format(new Date());
        for (int i = 0; i < entries.size() - 1; i++) {
            // Older entries we don't have timestamps for. Use "?".
            lines.add("?                     " + "  " + entries.get(i));
        }
        if (!entries.isEmpty()) {
            lines.add(now + "  " + entries.get(entries.size() - 1));
        }
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HeapGuardian.LOGGER.warn("[BootTime] Could not write history: {}",
                e.getMessage());
        }
    }
}
