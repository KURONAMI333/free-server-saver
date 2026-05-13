package com.kuronami.heapguardian.monitor;

import net.neoforged.bus.api.Event;

/**
 * Fired on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} whenever
 * the heap-derived {@link ThrottleLevel} changes — either rising under
 * memory pressure or falling back during recovery (with hysteresis already
 * applied by {@link HeapMonitor}).
 *
 * <p>Modules that perform actual interventions ({@code RandomTickModule},
 * {@code SpawnThrottleModule}, etc.) subscribe to this event and adjust
 * their behavior based on the new tier. Keeping the throttling decision
 * separate from the interventions is what lets each module be toggled
 * independently in config without touching the monitor.
 *
 * <p>This event is not cancellable and has no result — it's a notification,
 * not a hook.
 */
public class ThrottleLevelChangedEvent extends Event {

    private final ThrottleLevel previous;
    private final ThrottleLevel current;
    private final double heapPercent;
    private final long heapUsedBytes;
    private final long heapMaxBytes;

    public ThrottleLevelChangedEvent(
            ThrottleLevel previous,
            ThrottleLevel current,
            double heapPercent,
            long heapUsedBytes,
            long heapMaxBytes) {
        this.previous = previous;
        this.current = current;
        this.heapPercent = heapPercent;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
    }

    public ThrottleLevel previous() { return previous; }
    public ThrottleLevel current() { return current; }
    public double heapPercent() { return heapPercent; }
    public long heapUsedBytes() { return heapUsedBytes; }
    public long heapMaxBytes() { return heapMaxBytes; }

    /** True if the new tier is more aggressive (heap rising). */
    public boolean isEscalation() {
        return current.ordinal() > previous.ordinal();
    }

    /** True if the new tier is less aggressive (heap recovering). */
    public boolean isRecovery() {
        return current.ordinal() < previous.ordinal();
    }
}
