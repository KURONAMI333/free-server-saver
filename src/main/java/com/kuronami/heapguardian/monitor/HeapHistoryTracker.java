package com.kuronami.heapguardian.monitor;

import com.kuronami.heapguardian.config.HeapGuardianConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Ring buffer of recent {@link ThrottleLevelChangedEvent}s for the
 * {@code /heapguardian history} command.
 *
 * <p>Why bother keeping history? Two reasons:
 * <ol>
 *   <li><strong>Post-mortem.</strong> An Aternos operator who comes back
 *       to "the server crashed earlier" wants to see whether the heap
 *       was already climbing before the crash — was Heap Guardian
 *       intervening, did it reach L4? The vanilla logs answer that but
 *       are awkward to grep. In-game `/heapguardian history` answers it
 *       in one command.</li>
 *   <li><strong>Tuning feedback.</strong> If the user sees the heap
 *       flapping NORMAL↔L1 every minute, the modpack probably needs
 *       FerriteCore or a bigger -Xmx; we couldn't have surfaced that
 *       without a record of transitions.</li>
 * </ol>
 *
 * <p>Capacity is bounded ({@link HeapGuardianConfig#HISTORY_SIZE}) and
 * the buffer is cleared on server stop so it doesn't bleed across
 * sessions in dev runClient cycles.
 */
public class HeapHistoryTracker {

    public record Entry(
            long timestampMs,
            ThrottleLevel previous,
            ThrottleLevel current,
            double heapPercent) {
    }

    /**
     * {@link ArrayDeque} as a fixed-size ring: push to head, pop from tail
     * when full. The deque is small (≤ HISTORY_SIZE) so iteration cost is
     * trivial. Synchronized on the deque itself — only the monitor thread
     * pushes, but the command can read concurrently.
     */
    private final Deque<Entry> buffer = new ArrayDeque<>();

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        int capacity = HeapGuardianConfig.HISTORY_SIZE.get();
        Entry entry = new Entry(
            System.currentTimeMillis(),
            event.previous(),
            event.current(),
            event.heapPercent());

        synchronized (buffer) {
            buffer.addFirst(entry);
            while (buffer.size() > capacity) {
                buffer.removeLast();
            }
        }
    }

    /** Snapshot of the current buffer, newest first. Safe to iterate without locking. */
    public List<Entry> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /** Wipe on server stop so dev runClient cycles start clean. */
    public void clear() {
        synchronized (buffer) {
            buffer.clear();
        }
    }
}
