package com.kuronami.freeserversaver.environment;

import com.kuronami.freeserversaver.HeapGuardian;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Logs the JVM / hardware environment once at server start.
 *
 * <p>This is Aternos Heap Guardian's most Aternos-specific module —
 * not because it does anything Aternos-only, but because Aternos players
 * have <strong>no other way</strong> to see what RAM their server is
 * actually running with. The Aternos panel shows "your server has 2.5GB"
 * but doesn't tell you whether the RAM Boost reward from Medal is
 * actually applied this session, or whether you accidentally fell back
 * to base allocation.
 *
 * <p>This module logs the truth at server start:
 * <ul>
 *   <li>JVM max heap (the actual {@code -Xmx} ceiling)</li>
 *   <li>Current heap usage post-boot</li>
 *   <li>Available processor count</li>
 *   <li>JVM vendor / version</li>
 *   <li>Detected GC algorithm names</li>
 * </ul>
 *
 * <p>The same snapshot is exposed via {@code /freeserversaver env} so a
 * player who wasn't watching the boot log can still see it.
 *
 * <h3>Why not detect RAM Boost activations at runtime?</h3>
 * <p>I considered polling {@link Runtime#maxMemory()} every minute and
 * firing a notification when it changes. It can't work: the JVM's heap
 * ceiling is fixed at process start and doesn't change without a JVM
 * restart. Aternos's RAM Boost takes effect by restarting your server
 * with a different {@code -Xmx}, which means the new value is whatever
 * we read in the next server-start callback. Hence: log at start, expose
 * via command, no polling.
 *
 * <h3>Why store the snapshot statically?</h3>
 * <p>The command needs to read it. Server-start runs once, so a static
 * field captures the value cleanly. Alternative (re-reading at command
 * time) would show <em>current</em> values, not the value the heap
 * thresholds were calibrated against — confusing if the user is trying
 * to figure out why a recent threshold change happened.
 */
public class EnvironmentInspector {

    private static volatile EnvironmentSnapshot lastSnapshot = null;

    /** Public-readable bundle of the values logged at start. */
    public record EnvironmentSnapshot(
            long heapMaxBytes,
            long heapUsedBytes,
            int availableProcessors,
            String jvmVendor,
            String jvmVersion,
            String javaSpecVersion,
            String startedAt) {

        public long heapMaxMB() { return heapMaxBytes / 1_048_576L; }
        public long heapUsedMB() { return heapUsedBytes / 1_048_576L; }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        RuntimeMXBean runBean = ManagementFactory.getRuntimeMXBean();

        EnvironmentSnapshot snap = new EnvironmentSnapshot(
            heap.getMax(),
            heap.getUsed(),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.vm.vendor", "unknown"),
            System.getProperty("java.vm.version", "unknown"),
            System.getProperty("java.specification.version", "unknown"),
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date()));
        lastSnapshot = snap;

        HeapGuardian.LOGGER.info(
            "=== Aternos Heap Guardian: environment snapshot ===");
        HeapGuardian.LOGGER.info(
            "  Heap max     : {} MB", snap.heapMaxMB());
        HeapGuardian.LOGGER.info(
            "  Heap used    : {} MB (post-boot, expect to climb)",
            snap.heapUsedMB());
        HeapGuardian.LOGGER.info(
            "  CPU cores    : {}", snap.availableProcessors());
        HeapGuardian.LOGGER.info(
            "  JVM          : {} {} (Java {})",
            snap.jvmVendor(), snap.jvmVersion(), snap.javaSpecVersion());
        HeapGuardian.LOGGER.info(
            "  Uptime so far: {} ms", runBean.getUptime());

        // Aternos-specific reality check: free tier is ~2.5 GB. If we see
        // less than 2 GB, the user probably has a misconfiguration; more
        // than 3.5 GB suggests Medal RAM Boost is active.
        long heapMB = snap.heapMaxMB();
        if (heapMB < 2_000) {
            HeapGuardian.LOGGER.warn(
                "  ⚠ Heap is below 2 GB. If you're on Aternos free tier you "
                + "may have a misconfiguration — base tier should give ~2.5 GB.");
        } else if (heapMB > 3_500) {
            HeapGuardian.LOGGER.info(
                "  ✓ Heap is above 3.5 GB — looks like Medal RAM Boost is "
                + "active. Throttling will engage at higher absolute "
                + "thresholds this session.");
        } else {
            HeapGuardian.LOGGER.info(
                "  Standard Aternos-grade heap. Thresholds calibrated.");
        }
        HeapGuardian.LOGGER.info("===================================================");
    }

    /** Snapshot the start-of-session values. Null until first server start. */
    public static EnvironmentSnapshot lastSnapshot() {
        return lastSnapshot;
    }
}
