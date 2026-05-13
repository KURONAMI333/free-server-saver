package com.kuronami.aternosguardian;

import com.kuronami.aternosguardian.command.HeapGuardianCommand;
import com.kuronami.aternosguardian.compat.ModCompatWarnings;
import com.kuronami.aternosguardian.config.HeapGuardianConfig;
import com.kuronami.aternosguardian.monitor.HeapMonitor;
import com.kuronami.aternosguardian.environment.EnvironmentInspector;
import com.kuronami.aternosguardian.modules.ChunkUnloadModule;
import com.kuronami.aternosguardian.modules.DespawnModule;
import com.kuronami.aternosguardian.modules.DiscordWebhookModule;
import com.kuronami.aternosguardian.modules.EntityTickThrottleModule;
import com.kuronami.aternosguardian.modules.ItemEntityThrottleModule;
import com.kuronami.aternosguardian.modules.MobDensityDetector;
import com.kuronami.aternosguardian.modules.SpawnThrottleModule;
import com.kuronami.aternosguardian.modules.TickRateModule;
import com.kuronami.aternosguardian.monitor.HeapHistoryTracker;
import com.kuronami.aternosguardian.monitor.LagSpikeDetector;
import com.kuronami.aternosguardian.tuning.AutoTuner;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Heap Guardian — entry point.
 *
 * <p>Heap-aware adaptive throttle for low-RAM Minecraft servers. Polls
 * the JVM heap on every {@code ServerTickEvent.Post} and gradually scales
 * back random ticks, mob spawns, chunk loads, and (in the most extreme
 * tier) forces distant mob despawns. The goal is to keep heap pressure
 * below the threshold where the GC starts producing the long pause times
 * that look like network lag on Aternos-grade hardware.
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
 *   <li>{@link HeapGuardianCommand} — {@code /aternosguardian status|history|metrics|inspect}</li>
 * </ul>
 *
 * <p>See {@code claude-memory/kuronami-mods/knowledge/HEAP_GUARDIAN_NOTES.md}
 * for the design rationale of each module.
 */
@Mod(HeapGuardian.MOD_ID)
public class HeapGuardian {

    public static final String MOD_ID = "aternosguardian";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public HeapGuardian(IEventBus modBus, ModContainer container) {
        LOGGER.info("Heap Guardian starting (Phase 1+2+3).");

        // Config is loaded via the mod container; it lives in
        // serverconfig/aternosguardian-server.toml once a world has been
        // started, but the spec itself has to be registered here, on the
        // mod bus, during construction.
        container.registerConfig(ModConfig.Type.SERVER, HeapGuardianConfig.SPEC);

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
        HeapGuardianCommand command = new HeapGuardianCommand(monitor, history, lagSpikes, autoTuner);

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
        NeoForge.EVENT_BUS.register(command);

        // ModCompatWarnings is a static utility (no per-instance state),
        // so register the class itself.
        NeoForge.EVENT_BUS.register(ModCompatWarnings.class);
    }
}
