package com.kuronami.freeserversaver.compat;

import com.kuronami.freeserversaver.FreeServerSaver;
import com.kuronami.freeserversaver.exceptionguard.ExceptionGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Detects loaded mods that overlap with Free Server Saver's scope and
 * disables our overlapping modules so we don't multiply effects.
 *
 * <p>Two throttle-style throttling mods running together create a multiplicative
 * problem: if both decide independently to tick a mob every 4th tick,
 * the result isn't "ticked every 4th tick by one of them" — it's "ticked
 * every 4th×4th tick combined into who-cancels-first." A mob that should
 * be at 25% AI rate ends up at 6% or worse, depending on tick ordering.
 *
 * <p>The fix is to step out of the lane the other mod is already in:
 * <ul>
 *   <li>If a distance-bucket mob-throttle mod (DAB / WMB / Immersive
 *       Optimization / OptimizeMod / No See No Tick) is loaded, we
 *       don't run our {@code EntityTickThrottleModule}.</li>
 *   <li>If a tick-rate-adaptive mod (Tick Dynamic / Tick Tweaks) is
 *       loaded, we don't run our {@code TickRateModule} (no two mods
 *       should fight over /tick rate).</li>
 *   <li>If APT-Spawn is loaded, we don't run our
 *       {@code SpawnThrottleModule} (both hook FinalizeSpawnEvent and
 *       APT's rules are more granular).</li>
 *   <li>If a distance-based item-tick mod is loaded, we don't run our
 *       {@code ItemEntityThrottleModule}.</li>
 * </ul>
 *
 * <p>This is a "first writer wins" approach — we yield to the other
 * mod entirely rather than try to coexist. Trying to merge throttle
 * decisions across mods is a well-known nightmare in the Forge/Bukkit
 * world; better to be predictable than clever.
 *
 * <p>Free Server Saver's <em>other</em> modules — heap monitor itself,
 * spawn cancel-of-cancel logic, chunk view-distance, L4 despawn,
 * Discord webhook, lag-spike detector, mob-density detector,
 * environment inspector — don't have direct competitors and are not
 * touched here.
 *
 * <h3>Why not just let the events stack?</h3>
 * <p>For {@code FinalizeSpawnEvent} the events stack fine because the
 * second-priority handler reads {@code isSpawnCancelled()} and bails.
 * But for {@code EntityTickEvent.Pre}, both handlers fire even if the
 * event is cancelled (Forge's cancellable event semantics let later
 * handlers see and potentially un-cancel), and even if they didn't,
 * our distance computation runs before any decision — that's the
 * compute we want to save.
 */
public final class CompatibilityCoordinator {

    /** Distance-bucket mob throttling — same lane as EntityTickThrottleModule. */
    private static final List<String> MOB_TICK_THROTTLE_MODS = List.of(
        "wheres_my_brain",
        "immersive_optimization",
        "optimizemod",
        "no_see_no_tick",
        "martensite_ets",
        "tick_tweaks"  // covers item + block-entity + living-entity throttling
    );

    /** /tick rate or equivalent — same lane as TickRateModule. */
    private static final List<String> TICK_RATE_MODS = List.of(
        "tickdynamic",
        "tickratechanger",
        "tick_tweaks"
    );

    /** Spawn cancellation — same lane as SpawnThrottleModule. */
    private static final List<String> SPAWN_THROTTLE_MODS = List.of(
        "adaptive_performance_tweaks_spawn",
        "adaptive_performance_tweaks"  // the bundled version
    );

    /** Item-entity-specific throttling — same lane as ItemEntityThrottleModule. */
    private static final List<String> ITEM_THROTTLE_MODS = List.of(
        "no_see_no_tick",
        "optimizemod",
        "immersive_optimization",
        "tick_tweaks"
    );

    /** Chunk pre-generation — same lane as ChunkPreGenModule. Chunky is the gold standard. */
    private static final List<String> CHUNK_PREGEN_MODS = List.of(
        "chunky",
        "chunkpregen"
    );

    /**
     * Exception-guard mods — same lane as ExceptionGuard (Phase 13).
     * Neruina is the industry standard with 48M+ downloads and a much more
     * sophisticated approach (per-entity NBT persistence, interactive
     * suspend/resume UI). If it's present we yield entirely — running both
     * would mean two mixins fighting over the same try/catch site.
     */
    private static final List<String> EXCEPTION_GUARD_MODS = List.of(
        "neruina",
        "bugfix",
        "failsafe",
        "crashbacktotitle"
    );

    /** Static flags. Set once at server start; read by each module's event handler. */
    private static final AtomicBoolean yieldEntityTick = new AtomicBoolean(false);
    private static final AtomicBoolean yieldSpawn = new AtomicBoolean(false);
    private static final AtomicBoolean yieldTickRate = new AtomicBoolean(false);
    private static final AtomicBoolean yieldItemTick = new AtomicBoolean(false);
    private static final AtomicBoolean yieldChunkPregen = new AtomicBoolean(false);

    private CompatibilityCoordinator() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ModList list = ModList.get();

        // Compute each yield decision. Logging the specific competing
        // mod is the operator's only debugging clue when a module
        // silently doesn't run.
        evaluate(list, MOB_TICK_THROTTLE_MODS, yieldEntityTick, "EntityTickThrottleModule");
        evaluate(list, TICK_RATE_MODS, yieldTickRate, "TickRateModule");
        evaluate(list, SPAWN_THROTTLE_MODS, yieldSpawn, "SpawnThrottleModule");
        evaluate(list, ITEM_THROTTLE_MODS, yieldItemTick, "ItemEntityThrottleModule");
        evaluate(list, CHUNK_PREGEN_MODS, yieldChunkPregen, "ChunkPreGenModule");

        // ExceptionGuard yields by toggling its own enabled flag rather
        // than gating an event handler — the mixin checks the flag every
        // tick. We don't keep a separate AtomicBoolean here because the
        // single source of truth is ExceptionGuard's own flag.
        for (String id : EXCEPTION_GUARD_MODS) {
            if (list.isLoaded(id)) {
                ExceptionGuard.setEnabled(false);
                FreeServerSaver.LOGGER.info(
                    "[Compat] Yielding ExceptionGuard to '{}' (overlapping scope; "
                    + "running both would mean two mixins fighting over the same "
                    + "tick try/catch site).", id);
                break;
            }
        }
    }

    /**
     * If any of the listed competitor mods is loaded, set the yield
     * flag and log the reason. The first competitor name is logged —
     * if multiple are loaded the operator's setup is already chaotic
     * and the extra info won't help.
     */
    private static void evaluate(ModList list, List<String> competitors,
                                 AtomicBoolean flag, String moduleName) {
        for (String id : competitors) {
            if (list.isLoaded(id)) {
                flag.set(true);
                FreeServerSaver.LOGGER.info(
                    "[Compat] Yielding {} to '{}' (overlapping scope; running both would "
                    + "multiply throttle effects). Re-enable in our config only if you "
                    + "have confirmed the other mod isn't doing this job already.",
                    moduleName, id);
                return;
            }
        }
    }

    /** True if a competitor for {@code EntityTickThrottleModule} is loaded. */
    public static boolean yieldEntityTickThrottle() {
        return yieldEntityTick.get();
    }

    /** True if a competitor for {@code SpawnThrottleModule} is loaded. */
    public static boolean yieldSpawnThrottle() {
        return yieldSpawn.get();
    }

    /** True if a competitor for {@code TickRateModule} is loaded. */
    public static boolean yieldTickRate() {
        return yieldTickRate.get();
    }

    /** True if a competitor for {@code ItemEntityThrottleModule} is loaded. */
    public static boolean yieldItemThrottle() {
        return yieldItemTick.get();
    }

    /** True if a competitor for {@code ChunkPreGenModule} is loaded (Chunky etc.). */
    public static boolean yieldChunkPregen() {
        return yieldChunkPregen.get();
    }
}
