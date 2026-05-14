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
 * the JVM heap on every {@code ServerTickEvent.Post} and gradually scales
 * back random ticks, mob spawns, chunk loads, and (in the most extreme
 * tier) forces distant mob despawns. The goal is to keep heap pressure
 * below the threshold where the GC starts producing the long pause times
 * that look like network lag on low-RAM hardware.
 *
 * <p>v0.1.0 Phase 1+2+3 scope:
 * <ul>
 *   <li>{@link HeapMonitor} — polling + tier classification</li>
 *   <li>{@link HeapHistoryTracker} — ring buffer of recent transitions</li>
 *   <li>{@link EntityTickThrottleModule} — distance-bucketed AI tick throttling (L1+)</li>
 *   <li>{@link SpawnThrottleModule} — mob spawn cancellation (L1+)</li>
 *   <li>{@link ChunkUnloadModule} — view/simulation distance scaling (L3+)</li>
 *   <li>{@link DespawnModule} — emergency mob sweep on L4 entry</li>
 *   <li>{@link TickRateModule} — emergency tick-rate halving on L4</li>
 *   <li>{@link DiscordWebhookModule} — async Discord notifications</li>
 *   <li>{@link ModCompatWarnings} — startup warnings for overlapping mods</li>
 *   <li>{@link FreeServerSaverCommand} — {@code /freeserversaver status|history|metrics|inspect}</li>
 * </ul>
 *
 * <p>See {@code claude-memory/kuronami-mods/knowledge/FREE_SERVER_SAVER_NOTES.md}
 * for the design rationale of each module.
 */
@Mod(FreeServerSaver.MOD_ID)
public class FreeServerSaver {

    public static final String MOD_ID = "freeserversaver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FreeServerSaver(IEventBus modBus, ModContainer container) {
        // Record JVM-uptime-at-mod-construct. BootTimeTracker uses this
        // (vs ServerStartedEvent fire time) to compute boot duration.
        BootTimeTracker.recordModConstructed();

        LOGGER.info("Free Server Saver starting (heap-aware adaptive throttle, Phase 1-13).");

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
