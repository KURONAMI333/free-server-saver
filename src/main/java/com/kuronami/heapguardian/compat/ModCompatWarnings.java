package com.kuronami.heapguardian.compat;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.config.HeapGuardianConfig;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Warns at server start about loaded mods that interact poorly with the
 * throttling strategy Heap Guardian uses.
 *
 * <p>Two categories:
 * <dl>
 *   <dt><strong>HEAVY_MODS</strong></dt>
 *   <dd>Tech mods with large numbers of persistent block entities
 *       (Create gearboxes, Mekanism reactors, etc.). These don't fight
 *       Heap Guardian, but their per-tick allocation rate can outrun our
 *       recovery on a 2 GB heap. The warning gives the user a heads-up
 *       that "Heap Guardian alone may not be enough — also install
 *       FerriteCore / ModernFix for static reductions."</dd>
 *   <dt><strong>CONFLICTING_MODS</strong></dt>
 *   <dd>Other adaptive performance mods that may double-cancel events
 *       or fight us over gamerule values. We don't refuse to load — both
 *       can coexist and the user may want to — but we surface the
 *       overlap so the user can decide.</dd>
 * </dl>
 *
 * <p>This is intentionally a warning, not a hard refusal: the user knows
 * their own setup better than we do, and a "won't even load" failure mode
 * for a server-side mod on Aternos is far worse than a log line.
 */
public final class ModCompatWarnings {

    /** Tech mods whose BE-heavy workloads can defeat our recovery rate. */
    private static final List<String> HEAVY_MODS = List.of(
        "create",
        "mekanism",
        "industrialforegoing",
        "thermal",
        "refinedstorage",
        "ae2",
        "appliedenergistics2",
        "bigreactors",
        "biggerreactors"
    );

    /** Other adaptive performance mods that overlap with our scope. */
    private static final List<String> CONFLICTING_MODS = List.of(
        "adaptive_performance_tweaks_core",
        "adaptive_performance_tweaks_spawn",
        "tickdynamic",
        "tick_tweaks"
    );

    private ModCompatWarnings() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_MOD_COMPAT_WARNINGS.get())) {
            return;
        }

        ModList list = ModList.get();

        for (String id : HEAVY_MODS) {
            if (list.isLoaded(id)) {
                HeapGuardian.LOGGER.warn(
                    "Detected heavy mod '{}' — its block entities may allocate "
                    + "faster than Heap Guardian can recover. Recommended companion: "
                    + "FerriteCore + ModernFix for static memory reductions.",
                    id);
            }
        }

        for (String id : CONFLICTING_MODS) {
            if (list.isLoaded(id)) {
                HeapGuardian.LOGGER.warn(
                    "Detected adaptive perf mod '{}' — runs alongside Heap Guardian "
                    + "without conflict, but both may adjust mob spawns / random "
                    + "ticks. If you see surprising behavior, try disabling one.",
                    id);
            }
        }
    }
}
