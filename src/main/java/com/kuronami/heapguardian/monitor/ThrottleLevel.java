package com.kuronami.heapguardian.monitor;

/**
 * Five-tier throttle scale based on heap usage percentage.
 *
 * <p>Thresholds use a fixed ladder rather than configurable knobs — the
 * point of this MOD is that "good defaults for a 2-4GB server" already
 * encodes the JVM-level behavior we're working around (G1GC starting to
 * burn CPU on collection cycles when heap occupancy climbs past ~60-70%).
 * Per-server tuning happens in Phase 2 if real users need it.
 *
 * <p>Hysteresis: levels are entered at the {@code enterAt} threshold but
 * only released when heap drops by {@link #HYSTERESIS_MARGIN}% below it.
 * This prevents oscillation around a single boundary when the heap is
 * sitting right on it.
 */
public enum ThrottleLevel {

    /** Below 60%. No interventions; vanilla behavior. */
    NORMAL(0.0),

    /** 60-70%. randomTickSpeed 3→1, 50% mob spawn rejection. */
    L1_MILD(60.0),

    /** 70-80%. randomTickSpeed→0, all spawns canceled, distant mob AI frozen. */
    L2_HEAVY(70.0),

    /** 80-85%. + aggressive chunk unload (ChunkPurge-style flood fill). */
    L3_AGGRESSIVE(80.0),

    /** 85%+. + force-discard distant mobs, optional explicit System.gc(). */
    L4_EMERGENCY(85.0);

    /** Percentage points of margin required before releasing a level. */
    public static final double HYSTERESIS_MARGIN = 5.0;

    private final double enterAt;

    ThrottleLevel(double enterAt) {
        this.enterAt = enterAt;
    }

    public double enterAt() {
        return enterAt;
    }

    /**
     * Compute the tier the given heap percentage falls into.
     *
     * <p>This is the "rising edge" mapping — used when the heap is climbing.
     * For the "falling edge" (heap recovering), callers must apply hysteresis
     * by checking against {@link #enterAt()} minus {@link #HYSTERESIS_MARGIN}.
     */
    public static ThrottleLevel forHeapPercent(double pct) {
        if (pct >= L4_EMERGENCY.enterAt) return L4_EMERGENCY;
        if (pct >= L3_AGGRESSIVE.enterAt) return L3_AGGRESSIVE;
        if (pct >= L2_HEAVY.enterAt) return L2_HEAVY;
        if (pct >= L1_MILD.enterAt) return L1_MILD;
        return NORMAL;
    }
}
