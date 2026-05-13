package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.compat.CompatibilityCoordinator;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Simple synchronous chunk pre-generator for {@code /freeserversaver pregen}.
 *
 * <p>Aternos's other persistent pain point: the first player to explore
 * a new area triggers chunk generation, which on a constrained server
 * produces visible stutter ("chunk loading" lag). Chunky is the mature
 * solution — multi-threaded queue, persistence, fancy commands — but
 * if the user only wants "pregen the spawn area before opening the
 * server to friends," a single command is enough.
 *
 * <p>This module yields entirely to Chunky if Chunky is installed.
 * Running both would compete on the same loading queue; the operator
 * already chose their tool. Same pattern as
 * {@link CompatibilityCoordinator} for the throttle modules.
 *
 * <h3>Implementation</h3>
 * <p>Synchronous chunk-by-chunk via {@link ServerLevel#getChunk(int, int, ChunkStatus, boolean)}
 * with {@link ChunkStatus#FULL}. Synchronous means the server thread
 * is busy until done, so we cap the radius at {@link #MAX_RADIUS_CHUNKS}
 * to keep the command from freezing the server.
 *
 * <p>Why no async? Async chunk gen is what Chunky is built for, and
 * doing it right (thread safety, ticket lifecycle, save coordination)
 * is a multi-thousand-line effort. The whole point of this module is
 * "I just want a quick fill-the-spawn command." If the user wants more,
 * Chunky is the right answer.
 */
public class ChunkPreGenModule {

    /** Maximum chunks-radius accepted by the command. 16 = 256 blocks, ~1024 chunks. */
    public static final int MAX_RADIUS_CHUNKS = 16;

    private MinecraftServer server;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    /**
     * Pre-generate a square region centered at the given chunk in the
     * given level. Returns the number of chunks actually generated
     * (already-loaded chunks aren't counted twice).
     *
     * @return generation count, or -1 if this module is yielding to a competitor
     */
    public int pregen(ServerLevel level, ChunkPos center, int radius) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_CHUNK_PREGEN.get())) {
            return -1;
        }
        if (CompatibilityCoordinator.yieldChunkPregen()) {
            HeapGuardian.LOGGER.info(
                "[ChunkPreGen] Chunky is installed — use /chunky instead for "
                + "better multi-threaded pregen.");
            return -1;
        }
        int r = Math.min(radius, MAX_RADIUS_CHUNKS);
        int generated = 0;

        // Synchronous walk. Each getChunk(...) call blocks until the
        // chunk is at FULL status, which is what we need for it to be
        // persisted to disk and serve normally to future players.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                // getChunk with load=true generates if missing.
                ChunkAccess ca = level.getChunk(cx, cz, ChunkStatus.FULL, true);
                if (ca != null) {
                    generated++;
                }
            }
        }
        HeapGuardian.LOGGER.info(
            "[ChunkPreGen] Generated/loaded {} chunks around ({}, {}) in {}.",
            generated, center.x, center.z, level.dimension().location());
        return generated;
    }
}
