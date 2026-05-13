package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Periodically scans loaded chunks for unusual mob concentrations
 * and logs a warning when a chunk holds too many mobs of the same type.
 * The classic signature of a misbehaving or unintended mob farm.
 *
 * <p>A normal chunk has fewer than 5 mobs at any time. An aggressively
 * running iron farm or zombie XP grinder can sit at 50-100 zombies in
 * one chunk indefinitely. That chunk alone can be the source of half
 * the server's entity tick cost; calling it out gives the operator the
 * information they need to either move the farm, throttle it manually,
 * or accept the trade-off.
 *
 * <p>Scan cadence: once every {@link #SCAN_INTERVAL_TICKS}
 * (~30 seconds). Iterating loaded chunks every tick would be a regression
 * worse than the problem we're trying to detect. Every 30 seconds catches
 * persistent concentrations without competing with the throttling modules
 * for CPU budget.
 *
 * <p>Detection is intentionally diagnostic only — this module doesn't
 * modify entity behavior. The throttling modules ({@link EntityTickThrottleModule},
 * {@link ItemEntityThrottleModule}) already apply distance-based tick
 * intervals to every mob anyway; a high-density chunk just experiences
 * its throttling sooner because the distance check still applies.
 */
public class MobDensityDetector {

    /** Run a scan every N ticks. 30 seconds at 20 TPS. */
    private static final int SCAN_INTERVAL_TICKS = 20 * 30;

    /** Same-type mob count in one chunk that triggers a warning. */
    private static final int CONCENTRATION_THRESHOLD = 30;

    /** Don't repeat the same warning for the same (chunk, type) within this many ticks. */
    private static final long REPEAT_SUPPRESS_TICKS = 20L * 60L * 5L; // 5 minutes

    /** chunk-pos packed long + type id → last-warned tick. */
    private final Map<Long, Long> lastWarnedAt = new HashMap<>();

    private MinecraftServer server;
    private int ticksUntilNextScan = SCAN_INTERVAL_TICKS;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        lastWarnedAt.clear();
        ticksUntilNextScan = SCAN_INTERVAL_TICKS;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        if (server == null) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_MOB_DENSITY_DETECTION.get())) {
            return;
        }
        if (--ticksUntilNextScan > 0) return;
        ticksUntilNextScan = SCAN_INTERVAL_TICKS;

        for (ServerLevel level : server.getAllLevels()) {
            scan(level);
        }
    }

    private void scan(ServerLevel level) {
        // (chunkPosLong, typeIdString) → count
        // Using a nested map would allocate more; pack chunk + a 32-bit
        // type hash into the same long-keyed map to avoid that.
        Map<ChunkTypeKey, Integer> counts = new HashMap<>();

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mob)) continue;
            ChunkPos cp = entity.chunkPosition();
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            ChunkTypeKey key = new ChunkTypeKey(cp.toLong(), typeId.toString());
            counts.merge(key, 1, Integer::sum);
        }

        long currentTick = level.getGameTime();
        for (Map.Entry<ChunkTypeKey, Integer> e : counts.entrySet()) {
            int count = e.getValue();
            if (count < CONCENTRATION_THRESHOLD) continue;

            // Suppress repeat: same (chunk, type) gets one warning every
            // 5 minutes, not one every 30 seconds.
            Long lastTick = lastWarnedAt.get(e.getKey().hashCode64());
            if (lastTick != null && currentTick - lastTick < REPEAT_SUPPRESS_TICKS) {
                continue;
            }
            lastWarnedAt.put(e.getKey().hashCode64(), currentTick);

            ChunkPos cp = new ChunkPos(e.getKey().chunkPosLong);
            HeapGuardian.LOGGER.warn(
                "[MobDensity] {} '{}' mobs in {} at chunk ({}, {}) — likely a mob farm. "
                + "Heap Guardian's distance throttling applies, but consider moving the farm "
                + "or limiting its output if heap pressure persists.",
                count, e.getKey().typeId, level.dimension().location(), cp.x, cp.z);
        }
    }

    /**
     * Composite key for the per-(chunk, type) count map. Keyed on a
     * {@code long} packing chunkX,chunkZ and a string for the type
     * to keep the hash trivially computable without allocating in the
     * inner loop more than necessary.
     */
    private record ChunkTypeKey(long chunkPosLong, String typeId) {
        long hashCode64() {
            return chunkPosLong ^ ((long) typeId.hashCode() << 32);
        }
    }
}
