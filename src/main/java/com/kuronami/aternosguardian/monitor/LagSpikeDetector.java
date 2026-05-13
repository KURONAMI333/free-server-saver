package com.kuronami.aternosguardian.monitor;

import com.kuronami.aternosguardian.HeapGuardian;
import com.kuronami.aternosguardian.config.HeapGuardianConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Records server tick durations and captures a context snapshot whenever
 * a tick exceeds the lag-spike threshold ({@link #SPIKE_THRESHOLD_MS}).
 *
 * <p>Spikes are the visible symptom of the very thing Heap Guardian is
 * trying to prevent — GC pauses, sudden chunk loads, mob herd movement,
 * etc. When throttling is configured correctly, spikes should be rare.
 * When they happen anyway, the operator needs to know:
 * <ul>
 *   <li>What heap usage was at that moment</li>
 *   <li>What throttle tier was active</li>
 *   <li>How many players / how many ticks ago</li>
 * </ul>
 * to figure out whether the spike was something Heap Guardian could
 * have prevented or something outside its scope (a network burst, a
 * GC that ignored our tier, etc.).
 *
 * <p>{@code /aternosguardian lagspikes} dumps the buffer. Spark exists
 * for full profiler-grade analysis — this module is the
 * "always-on lightweight breadcrumb trail" so a player who didn't have
 * spark running can still see what happened.
 *
 * <h3>Implementation notes</h3>
 * <ul>
 *   <li>Tick duration is measured with {@code System.nanoTime()} between
 *       {@code ServerTickEvent.Pre} and {@code ServerTickEvent.Post}.
 *       This gives per-tick fidelity that {@code MinecraftServer.getTickTime()}
 *       (which is a rolling average over 100 ticks) hides.</li>
 *   <li>The snapshot captured at spike time is intentionally minimal —
 *       just numbers, no entity iteration. Doing heavy work inside the
 *       handler that already fired late would extend the spike further.</li>
 *   <li>The ring buffer holds the most recent
 *       {@link HeapGuardianConfig#LAG_SPIKE_HISTORY_SIZE} entries. Old
 *       entries fall off; the operator can read them at any time before
 *       they age out.</li>
 * </ul>
 */
public class LagSpikeDetector {

    /**
     * Threshold for considering a tick a "spike."
     * 100ms = 10 TPS dipped for one tick. Anything above this is
     * already noticeable to players.
     */
    private static final long SPIKE_THRESHOLD_MS = 100;

    public record Entry(
            long timestampMs,
            long msptObserved,
            double heapPercent,
            ThrottleLevel throttleAtSpike,
            int playerCount) {
    }

    private final HeapMonitor heapMonitor;

    private long tickStartNanos = 0;
    private boolean armed = false;
    private final Deque<Entry> buffer = new ArrayDeque<>();

    public LagSpikeDetector(HeapMonitor heapMonitor) {
        this.heapMonitor = heapMonitor;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        synchronized (buffer) {
            buffer.clear();
        }
        armed = true;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        armed = false;
    }

    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        if (!armed) return;
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (!armed || tickStartNanos == 0) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_LAG_SPIKE_DETECTION.get())) {
            return;
        }

        long durationNanos = System.nanoTime() - tickStartNanos;
        long mspt = durationNanos / 1_000_000L;

        if (mspt < SPIKE_THRESHOLD_MS) {
            return;
        }

        // Snapshot: numbers only. No entity iteration, no level scan —
        // the tick has already overrun, doing more work now extends it.
        Entry entry = new Entry(
            System.currentTimeMillis(),
            mspt,
            heapMonitor.lastHeapPercent(),
            heapMonitor.currentLevel(),
            // PlayerList exposes count cheaply; iterating level.players()
            // for each level would be heavier and not worth it for the
            // breadcrumb purpose.
            playerCount(event));

        int capacity = HeapGuardianConfig.LAG_SPIKE_HISTORY_SIZE.get();
        synchronized (buffer) {
            buffer.addFirst(entry);
            while (buffer.size() > capacity) {
                buffer.removeLast();
            }
        }

        // Log too — operators reading server logs after a complaint can
        // grep the line directly without going in-game.
        HeapGuardian.LOGGER.warn(
            "[LagSpike] {}ms tick (heap {}%, tier {}, players {})",
            mspt,
            String.format("%.1f", entry.heapPercent()),
            entry.throttleAtSpike(),
            entry.playerCount());
    }

    private int playerCount(ServerTickEvent.Post event) {
        // ServerTickEvent.Post exposes getServer() on NeoForge 1.21.
        var server = event.getServer();
        return server == null ? 0 : server.getPlayerList().getPlayerCount();
    }

    /** Snapshot of recent spikes, newest first. Safe to iterate without locking. */
    public List<Entry> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }
}
