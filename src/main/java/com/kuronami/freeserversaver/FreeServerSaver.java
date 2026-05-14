package com.kuronami.freeserversaver;

import com.kuronami.freeserversaver.command.FreeServerSaverCommand;
import com.kuronami.freeserversaver.compat.CompatibilityCoordinator;
import com.kuronami.freeserversaver.compat.ModCompatWarnings;
import com.kuronami.freeserversaver.config.FreeServerSaverConfig;
import com.kuronami.freeserversaver.monitor.HeapMonitor;
import com.kuronami.freeserversaver.environment.EnvironmentInspector;
import com.kuronami.freeserversaver.modules.ChunkPreGenModule;
import com.kuronami.freeserversaver.modules.ChunkPruningModule;
import com.kuronami.freeserversaver.modules.ChunkUnloadModule;
import com.kuronami.freeserversaver.modules.DespawnModule;
import com.kuronami.freeserversaver.modules.DiscordWebhookModule;
import com.kuronami.freeserversaver.modules.EntityTickThrottleModule;
import com.kuronami.freeserversaver.modules.IdleTimerNotifier;
import com.kuronami.freeserversaver.modules.ItemEntityThrottleModule;
import com.kuronami.freeserversaver.modules.MobDensityDetector;
import com.kuronami.freeserversaver.modules.SpawnThrottleModule;
import com.kuronami.freeserversaver.modules.StorageMonitor;
import com.kuronami.freeserversaver.modules.TickRateModule;
import com.kuronami.freeserversaver.monitor.BootTimeTracker;
import com.kuronami.freeserversaver.monitor.HeapHistoryTracker;
import com.kuronami.freeserversaver.monitor.LagSpikeDetector;
import com.kuronami.freeserversaver.monitor.MetaspaceWatcher;
import com.kuronami.freeserversaver.tuning.AutoTuner;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Free Server Saver — entry point.
 *
 * <p>Heap-aware adaptive throttle for low-RAM Minecraft servers. Polls
 * the JVM heap every {@code ServerTickEvent.Post} and gradually scales
 * back mob AI ticks, spawn rates, and view distance before GC pauses
 * can fire — those pauses look identical to network lag from the
 * player's perspective and are the actual root cause behind "mobs
 * teleporting", "disconnected even though only 2 of us were online",
 * and intermittent crashes on 2-4 GB hosts.
 *
 * <p>Module responsibilities (each registered on {@code NeoForge.EVENT_BUS}
 * in the constructor below):
 * <ul>
 *   <li>{@link HeapMonitor} — polling loop, 5-tier classification with hysteresis</li>
 *   <li>{@link HeapHistoryTracker} — ring buffer of recent transitions</li>
 *   <li>{@link MetaspaceWatcher} — separate watcher for the Metaspace pool
 *       (RAM Boost extends heap, not Metaspace)</li>
 *   <li>{@link LagSpikeDetector} — 100ms+ tick breadcrumbs with heap state</li>
 *   <li>{@link BootTimeTracker} — boot-duration history vs the 10-min cap</li>
 *   <li>{@link EnvironmentInspector} — server-start JVM snapshot</li>
 *   <li>{@link EntityTickThrottleModule} — distance-bucketed AI tick throttle</li>
 *   <li>{@link ItemEntityThrottleModule} — ground-item tick throttle</li>
 *   <li>{@link SpawnThrottleModule} — natural-spawn cancellation</li>
 *   <li>{@link ChunkUnloadModule} — view/simulation distance compression</li>
 *   <li>{@link DespawnModule} — emergency far-mob sweep at L4</li>
 *   <li>{@link TickRateModule} — emergency tick-rate halving at L4</li>
 *   <li>{@link ChunkPruningModule} — flood-fill identification of orphan chunks</li>
 *   <li>{@link ChunkPreGenModule} — synchronous chunk pre-generation</li>
 *   <li>{@link StorageMonitor} — world-directory size vs 4 GB cap</li>
 *   <li>{@link IdleTimerNotifier} — first-join welcome about idle timer</li>
 *   <li>{@link MobDensityDetector} — mob-farm signature scanner (opt-in)</li>
 *   <li>{@link AutoTuner} — PID-lite threshold adjustment (opt-in)</li>
 *   <li>{@link DiscordWebhookModule} — async webhook for tier escalations (opt-in)</li>
 *   <li>{@code ExceptionGuard} (Mixin-based) — auto-quarantine for entities
 *       and block-entities that throw repeating tick exceptions</li>
 *   <li>{@link CompatibilityCoordinator} — yields overlapping modules
 *       when competitor mods are present (Lithium, FerriteCore, Neruina, etc.)</li>
 *   <li>{@link ModCompatWarnings} — startup hints about companion mods</li>
 *   <li>{@link FreeServerSaverCommand} — {@code /freeserversaver | /fss} command tree</li>
 * </ul>
 */
@Mod(FreeServerSaver.MOD_ID)
public class FreeServerSaver {

    public static final String MOD_ID = "freeserversaver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FreeServerSaver(IEventBus modBus, ModContainer container) {
        // Record JVM-uptime-at-mod-construct. BootTimeTracker uses this
        // (vs ServerStartedEvent fire time) to compute boot duration.
        BootTimeTracker.recordModConstructed();

        LOGGER.info("Free Server Saver starting — heap-aware adaptive throttle for low-RAM servers.");

        // Config is loaded via the mod container; it lives in
        // serverconfig/freeserversaver-server.toml once a world has been
        // started, but the spec itself has to be registered here, on the
        // mod bus, during construction.
        container.registerConfig(ModConfig.Type.SERVER, FreeServerSaverConfig.SPEC);

        // Build the modules once so we have stable references to pass into
        // both the event bus subscriptions and the command. The command
        // needs to read live monitor + history state, so it has to share
        // the same instances the bus is feeding events to.
        HeapMonitor monitor = new HeapMonitor();
        HeapHistoryTracker history = new HeapHistoryTracker();
        EnvironmentInspector envInspector = new EnvironmentInspector();
        LagSpikeDetector lagSpikes = new LagSpikeDetector(monitor);
        EntityTickThrottleModule entityTick = new EntityTickThrottleModule();
        ItemEntityThrottleModule itemThrottle = new ItemEntityThrottleModule();
        SpawnThrottleModule spawnThrottle = new SpawnThrottleModule();
        ChunkUnloadModule chunkUnload = new ChunkUnloadModule();
        DespawnModule despawn = new DespawnModule();
        TickRateModule tickRate = new TickRateModule();
        DiscordWebhookModule webhook = new DiscordWebhookModule();
        MobDensityDetector mobDensity = new MobDensityDetector();
        AutoTuner autoTuner = new AutoTuner(monitor, lagSpikes);
        ChunkPruningModule chunkPruning = new ChunkPruningModule();
        StorageMonitor storage = new StorageMonitor();
        IdleTimerNotifier idleNotifier = new IdleTimerNotifier();
        BootTimeTracker bootTimer = new BootTimeTracker();
        ChunkPreGenModule chunkPregen = new ChunkPreGenModule();
        MetaspaceWatcher metaspace = new MetaspaceWatcher();
        FreeServerSaverCommand command = new FreeServerSaverCommand(
            monitor, history, lagSpikes, autoTuner, chunkPruning, storage, chunkPregen);

        // Game-bus subscriptions: everything that listens to server tick
        // / spawn / level events lives on NeoForge.EVENT_BUS, not the mod
        // bus. Mod bus is for registry/loader events only.
        NeoForge.EVENT_BUS.register(monitor);
        NeoForge.EVENT_BUS.register(history);
        NeoForge.EVENT_BUS.register(envInspector);
        NeoForge.EVENT_BUS.register(lagSpikes);
        NeoForge.EVENT_BUS.register(entityTick);
        NeoForge.EVENT_BUS.register(itemThrottle);
        NeoForge.EVENT_BUS.register(spawnThrottle);
        NeoForge.EVENT_BUS.register(chunkUnload);
        NeoForge.EVENT_BUS.register(despawn);
        NeoForge.EVENT_BUS.register(tickRate);
        NeoForge.EVENT_BUS.register(webhook);
        NeoForge.EVENT_BUS.register(mobDensity);
        NeoForge.EVENT_BUS.register(autoTuner);
        NeoForge.EVENT_BUS.register(chunkPruning);
        NeoForge.EVENT_BUS.register(storage);
        NeoForge.EVENT_BUS.register(idleNotifier);
        NeoForge.EVENT_BUS.register(bootTimer);
        NeoForge.EVENT_BUS.register(chunkPregen);
        NeoForge.EVENT_BUS.register(metaspace);
        NeoForge.EVENT_BUS.register(command);

        // ModCompatWarnings and CompatibilityCoordinator are static
        // utilities (no per-instance state), so register the class itself.
        // CompatibilityCoordinator must register before any throttle
        // module fires its first event — practically guaranteed because
        // its setup uses ServerStartingEvent, which fires before any
        // tick / spawn event.
        NeoForge.EVENT_BUS.register(ModCompatWarnings.class);
        NeoForge.EVENT_BUS.register(CompatibilityCoordinator.class);
    }
}
