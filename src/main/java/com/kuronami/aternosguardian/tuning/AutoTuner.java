package com.kuronami.aternosguardian.tuning;

import com.kuronami.aternosguardian.HeapGuardian;
import com.kuronami.aternosguardian.config.HeapGuardianConfig;
import com.kuronami.aternosguardian.monitor.HeapMonitor;
import com.kuronami.aternosguardian.monitor.LagSpikeDetector;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Periodically adjusts {@link HeapMonitor}'s threshold offset based on
 * observed heap and lag-spike patterns. The static thresholds
 * (60/70/80/85%) are a one-size-fits-all default; on real servers, the
 * "right" point to start throttling depends on heap allocation rate,
 * GC tuning, and modpack mix. Auto-tuning lets each server converge
 * on its own setting.
 *
 * <h3>Algorithm</h3>
 * <p>Every {@link #TUNE_INTERVAL_TICKS} (~5 minutes), compare two
 * counters from the most recent window:
 * <ul>
 *   <li><strong>Spikes at NORMAL/L1 tier</strong> — early spikes mean
 *       throttling didn't engage soon enough. Lower the offset
 *       (= start throttling earlier) by {@link #STEP_PERCENT}.</li>
 *   <li><strong>Heap consistently in L1/L2 but no spikes</strong> —
 *       throttling is engaging without need. Raise the offset
 *       (= start throttling later) by {@link #STEP_PERCENT}.</li>
 *   <li><strong>Heap moderate AND no spikes</strong> — nothing to do.</li>
 * </ul>
 *
 * <p>The offset is clamped to {@link #OFFSET_MIN}..{@link #OFFSET_MAX}
 * to keep the tuned thresholds within a sane range. At the extremes,
 * thresholds shift by ±10 percentage points from baseline.
 *
 * <h3>Why not a real PID controller?</h3>
 * <p>I sketched one. Adjusting thresholds is a discrete, slow-moving
 * actuator (we change it every 5 minutes), and the system noise is
 * dominated by player behavior (someone built a farm = step change
 * in workload). A 3-term PID with carefully chosen gains would track
 * the noise better than a simple step controller, but the noise we'd
 * be tracking isn't physical-system noise — it's "a player did
 * something." A step controller with hysteresis gives most of the
 * benefit with one configurable parameter instead of three.
 *
 * <h3>Decision boundaries</h3>
 * <p>"Spikes at low tier" = at least {@link #EARLY_SPIKE_TRIGGER}
 * spikes in the window where the active tier was NORMAL or L1_MILD.
 * "No spikes but consistent pressure" = max heap percent in the window
 * &gt; 65% AND zero spikes.
 */
public class AutoTuner {

    /** How often to evaluate. 5 minutes (~6000 ticks) balances responsiveness
     *  and stability — short enough to react to a misbehaving farm, long
     *  enough that one bad minute doesn't permanently shift the threshold. */
    private static final int TUNE_INTERVAL_TICKS = 20 * 60 * 5;

    /** Threshold shift per adjustment cycle, in percentage points. */
    private static final double STEP_PERCENT = 2.0;

    /** Don't shift beyond this many points below baseline. */
    private static final double OFFSET_MIN = -10.0;

    /** Don't shift beyond this many points above baseline. */
    private static final double OFFSET_MAX = 10.0;

    /** Spike count in window that signals "thresholds should be lower." */
    private static final int EARLY_SPIKE_TRIGGER = 2;

    /** Max heap percent in window that signals "consistent pressure." */
    private static final double CHRONIC_PRESSURE_PCT = 65.0;

    private final HeapMonitor heapMonitor;
    private final LagSpikeDetector lagSpikes;

    /**
     * Cumulative offset applied to all thresholds. Plus = throttle
     * later, minus = throttle earlier. Read by HeapMonitor at poll time
     * so changes take effect on the next 2-second poll, not gradually.
     */
    private volatile double currentOffset = 0.0;

    private int ticksUntilNextTune = TUNE_INTERVAL_TICKS;
    private int spikesAtLowTier = 0;
    private double maxHeapInWindow = 0.0;
    private boolean armed = false;

    public AutoTuner(HeapMonitor heapMonitor, LagSpikeDetector lagSpikes) {
        this.heapMonitor = heapMonitor;
        this.lagSpikes = lagSpikes;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        armed = true;
        currentOffset = 0.0;
        spikesAtLowTier = 0;
        maxHeapInWindow = 0.0;
        ticksUntilNextTune = TUNE_INTERVAL_TICKS;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        armed = false;
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (!armed) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_AUTO_TUNING.get())) {
            return;
        }

        // Accumulate window stats every tick — these are cheap field reads.
        double heap = heapMonitor.lastHeapPercent();
        if (heap > maxHeapInWindow) {
            maxHeapInWindow = heap;
        }

        if (--ticksUntilNextTune > 0) return;
        ticksUntilNextTune = TUNE_INTERVAL_TICKS;

        // Count spikes that happened at low tier (NORMAL or L1) in the
        // window. The lag-spike buffer is small (default 50) so this
        // iteration is cheap. We can't know exactly which spikes are
        // "from this window" without tracking entry-time-vs-window-start,
        // but the buffer is recent-only and old entries fall off in the
        // ring, so it's a good approximation.
        spikesAtLowTier = countEarlySpikes();

        evaluate();
    }

    private int countEarlySpikes() {
        int count = 0;
        for (LagSpikeDetector.Entry e : lagSpikes.snapshot()) {
            // Only consider spikes where the throttle hadn't reached L2+
            // — those are the ones a more-aggressive threshold could
            // have prevented.
            switch (e.throttleAtSpike()) {
                case NORMAL, L1_MILD -> count++;
                default -> { /* no-op */ }
            }
        }
        return count;
    }

    private void evaluate() {
        double previousOffset = currentOffset;

        if (spikesAtLowTier >= EARLY_SPIKE_TRIGGER) {
            // We had spikes the throttle didn't catch. Lower thresholds
            // = throttle engages sooner next time.
            currentOffset = Math.max(OFFSET_MIN, currentOffset - STEP_PERCENT);
        } else if (spikesAtLowTier == 0 && maxHeapInWindow > CHRONIC_PRESSURE_PCT
                   && maxHeapInWindow < 80.0) {
            // Heap was elevated but never spiked. Throttle is engaging
            // unnecessarily — raise thresholds.
            currentOffset = Math.min(OFFSET_MAX, currentOffset + STEP_PERCENT);
        }
        // Otherwise: heap moderate AND no spikes — no change. Don't
        // wander on quiet periods.

        if (Math.abs(currentOffset - previousOffset) > 0.01) {
            // Push the new offset into HeapMonitor. Volatile write; the
            // next poll (within 2 seconds) classifies against the new
            // effective thresholds.
            heapMonitor.setThresholdOffset(currentOffset);
            HeapGuardian.LOGGER.info(
                "[AutoTuner] Threshold offset adjusted: {} -> {} (spikes_at_low_tier={}, max_heap={}%)",
                String.format("%+.1f", previousOffset),
                String.format("%+.1f", currentOffset),
                spikesAtLowTier,
                String.format("%.1f", maxHeapInWindow));
        }

        // Reset window stats for next interval.
        maxHeapInWindow = heapMonitor.lastHeapPercent();
    }

    /**
     * Current threshold offset in percentage points. Used by HeapMonitor
     * to compute effective tier boundaries:
     * {@code effective_threshold = ThrottleLevel.enterAt() + currentOffset}.
     */
    public double currentOffset() {
        return currentOffset;
    }
}
