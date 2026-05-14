package com.kuronami.freeserversaver.exceptionguard;

import com.kuronami.freeserversaver.HeapGuardian;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;

/**
 * Singleton state holder for the ExceptionGuard module (Phase 13).
 *
 * <p>Watches per-entity / per-block-entity ticks via Mixin wrappers and
 * counts how often the same source throws an unhandled exception. After
 * {@link #THRESHOLD} exceptions within {@link #WINDOW_MS} milliseconds,
 * the source is flagged for quarantine — the caller's Mixin then calls
 * {@code Entity#discard()} or {@code LevelChunk#removeBlockEntity()}.
 *
 * <p>Why a counter rather than discarding on the first exception: vanilla
 * has legitimate one-off exceptions during chunk unload / entity reload
 * boundaries. Discarding on first hit would be over-aggressive and could
 * delete normal entities during edge cases. The 3-exceptions-per-10-seconds
 * rule matches the published <em>Neruina</em> pattern's spirit while being
 * stricter (Neruina suspends on first hit but provides an in-world UI to
 * resume — we don't have that UI, so we tolerate more before discarding).
 *
 * <h3>What we do NOT do</h3>
 * <ul>
 *   <li>Persist counters to NBT — counters reset on server restart, which
 *       is fine: if the entity is genuinely broken it will trip the
 *       counter again within seconds; if the issue was transient (a chunk
 *       boundary glitch) the reset gives it a second chance.</li>
 *   <li>Send a chat notification — Daisy's explicit instruction. Operators
 *       can run {@code /freeserversaver quarantine} to see the recent
 *       discard history.</li>
 *   <li>Modify block state — we only clear the block entity (the chest /
 *       furnace data); the block itself stays so any contents the player
 *       could see are still visually there. Reopening the block reinit'd
 *       a fresh block entity.</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * <p>Server tick is single-threaded so the per-source maps are safe to
 * update without synchronization. The {@link #enabled} flag is volatile
 * via {@link AtomicBoolean} because {@link com.kuronami.freeserversaver.compat.CompatibilityCoordinator}
 * sets it from {@code ServerStartingEvent} (server thread) and the Mixin
 * wrappers read it from {@code Level.guardEntityTick} (also server thread,
 * but the JIT may reorder otherwise).
 */
public final class ExceptionGuard {

    /** Exceptions within {@link #WINDOW_MS} milliseconds to trip quarantine. */
    public static final int THRESHOLD = 3;

    /** Time window (ms) for counting repeat exceptions per source. */
    public static final long WINDOW_MS = 10_000L;

    /** Cap on retained quarantine history shown by {@code /freeserversaver quarantine}. */
    public static final int HISTORY_CAP = 50;

    /**
     * Master enable flag. {@link com.kuronami.freeserversaver.compat.CompatibilityCoordinator}
     * sets this to {@code false} at server start if Neruina or another
     * exception-guard mod is loaded.
     */
    private static final AtomicBoolean ENABLED = new AtomicBoolean(true);

    /** UUID → list of recent exception timestamps (last {@link #WINDOW_MS} ms). */
    private static final Map<UUID, Deque<Long>> ENTITY_HITS = new HashMap<>();

    /** BlockPos → list of recent exception timestamps. */
    private static final Map<BlockPos, Deque<Long>> BLOCK_HITS = new HashMap<>();

    /** Recent quarantine events (newest first), capped at {@link #HISTORY_CAP}. */
    private static final Deque<QuarantineEntry> HISTORY = new ArrayDeque<>();

    /**
     * Wall-clock ms of the last housekeeping sweep. Without sweeping we'd
     * leak Map entries forever for entities that throw once and then despawn
     * naturally — the entry never reaches the threshold so it's never
     * removed by {@link #recordEntityException}. The sweep runs lazily
     * (next exception triggers it, never a background thread).
     */
    private static long lastSweepMs = 0L;
    /** Sweep at most every 60 seconds; cheap, but no reason to do more often. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private ExceptionGuard() {}

    /**
     * Master flag — true means Mixin wrappers should try-catch and count.
     * False means they should call {@code original.call} unconditionally
     * (i.e. yield to a competing exception-guard mod).
     */
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /** Set by {@code CompatibilityCoordinator}. Don't call from anywhere else. */
    public static void setEnabled(boolean v) {
        ENABLED.set(v);
    }

    /**
     * Record an entity-tick exception. Returns {@code true} if the
     * caller should quarantine (discard) the entity.
     *
     * @param entityId UUID of the offending entity
     * @param descriptor short human-readable description (entity type + position)
     * @param t the exception thrown
     * @return {@code true} if the entity exceeded the threshold and should be discarded
     */
    public static boolean recordEntityException(UUID entityId, String descriptor, Throwable t) {
        long now = System.currentTimeMillis();
        maybeSweep(now);
        Deque<Long> hits = ENTITY_HITS.computeIfAbsent(entityId, k -> new ArrayDeque<>(THRESHOLD));
        prune(hits, now);
        hits.addLast(now);

        // Always log once per hit so operators can correlate. Throttle to
        // a single line per hit (not a full stacktrace) to keep logs
        // readable. The top frame is what makes incident triage tractable.
        HeapGuardian.LOGGER.warn("[ExceptionGuard] Entity tick threw: {} — {} ({}/{})",
            descriptor, summarize(t), hits.size(), THRESHOLD);

        if (hits.size() >= THRESHOLD) {
            ENTITY_HITS.remove(entityId);
            addHistory(QuarantineEntry.ofEntity(now, descriptor, summarize(t)));
            return true;
        }
        return false;
    }

    /**
     * Record a block-entity-tick exception. Returns {@code true} if the
     * caller should remove the block entity (the block itself stays).
     */
    public static boolean recordBlockEntityException(BlockPos pos, String descriptor, Throwable t) {
        long now = System.currentTimeMillis();
        maybeSweep(now);
        Deque<Long> hits = BLOCK_HITS.computeIfAbsent(pos.immutable(), k -> new ArrayDeque<>(THRESHOLD));
        prune(hits, now);
        hits.addLast(now);

        HeapGuardian.LOGGER.warn("[ExceptionGuard] BlockEntity tick threw: {} — {} ({}/{})",
            descriptor, summarize(t), hits.size(), THRESHOLD);

        if (hits.size() >= THRESHOLD) {
            BLOCK_HITS.remove(pos.immutable());
            addHistory(QuarantineEntry.ofBlockEntity(now, descriptor, summarize(t)));
            return true;
        }
        return false;
    }

    /**
     * Snapshot of recent quarantines, newest first. Used by the command
     * tree. Returns a defensive copy — callers can iterate without
     * worrying about live mutation.
     */
    public static List<QuarantineEntry> recentQuarantines() {
        return new ArrayList<>(HISTORY);
    }

    private static void prune(Deque<Long> hits, long now) {
        long cutoff = now - WINDOW_MS;
        while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
            hits.pollFirst();
        }
    }

    /**
     * Housekeeping: walk both Maps and remove entries whose deque is
     * empty (after pruning by the WINDOW). Bounded to run at most every
     * {@link #SWEEP_INTERVAL_MS} ms; idempotent and cheap when the maps
     * are small. Called from the record() methods so the sweep happens
     * on-demand — no background thread needed.
     */
    private static void maybeSweep(long now) {
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) return;
        lastSweepMs = now;
        sweepMap(ENTITY_HITS, now);
        sweepMap(BLOCK_HITS, now);
    }

    private static <K> void sweepMap(Map<K, Deque<Long>> map, long now) {
        map.entrySet().removeIf(e -> {
            prune(e.getValue(), now);
            return e.getValue().isEmpty();
        });
    }

    private static void addHistory(QuarantineEntry entry) {
        HISTORY.addFirst(entry);
        while (HISTORY.size() > HISTORY_CAP) {
            HISTORY.pollLast();
        }
    }

    /**
     * Summarize a throwable in one line. Prefers the top stack frame
     * because that's what differs between two NPEs from different bugs;
     * the exception class alone is too coarse, the full message is too
     * noisy (often contains entity IDs that differ per-instance).
     */
    private static String summarize(Throwable t) {
        if (t == null) return "<null>";
        StackTraceElement[] trace = t.getStackTrace();
        String top = (trace != null && trace.length > 0)
            ? trace[0].getClassName() + "." + trace[0].getMethodName() + ":" + trace[0].getLineNumber()
            : "<no stack>";
        return t.getClass().getSimpleName() + " at " + top;
    }
}
