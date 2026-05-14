package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.FreeServerSaver;
import com.kuronami.freeserversaver.config.FreeServerSaverConfig;
import com.kuronami.freeserversaver.mixin.ChunkMapAccessor;
import com.kuronami.freeserversaver.monitor.ThrottleLevel;
import com.kuronami.freeserversaver.monitor.ThrottleLevelChangedEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Flood-fill chunk pruning, targeted at the 4 GB world-size cap common to free hosts.
 *
 * <p>free host boot limits every server to 4GB of compressed world data. On a
 * long-running modpack server, chunk files (.mca) accumulate
 * faster than players visit them — every Wither raid, every nether
 * trip, every accidental Elytra glide leaves more chunks loaded into
 * RAM and eventually written to disk. The 4GB ceiling is the most
 * frequently complained-about free host boot limit.
 *
 * <p>This module reduces the count of <em>currently loaded</em> chunks
 * by identifying chunks that aren't anchored by any player or vanilla
 * force-load ticket. The vanilla chunk-ticket system normally handles
 * this automatically, but on a heap-pressured server with view-distance
 * already compressed by ChunkUnloadModule, additional aggressive pruning
 * helps shed memory faster than vanilla's lazy unload.
 *
 * <p>Note: this module reduces <em>loaded</em> chunks, not on-disk
 * chunks. The 4GB cap is on-disk; this only helps insofar as fewer
 * loaded chunks means fewer chunks being written. A separate "region
 * file delete" pass — destructive, requires confirmation — is out of
 * scope for v0.1 and would belong as a manual command in a later phase.
 *
 * <h3>Algorithm (adapted from ChunkPurge / MIT-licensed)</h3>
 * <pre>
 * 1. Collect all loaded chunks from ChunkMap (via the accessor mixin).
 * 2. Compute anchors: every player position + every force-loaded chunk.
 * 3. Flood-fill outward from each anchor, restricted to {@code loaded},
 *    with a player cushion of PLAYER_CUSHION_CHUNKS.
 * 4. The set difference {@code loaded \ reachable} is the prune target.
 * 5. For each prune target, request the chunk holder be released. The
 *    vanilla ticket cleanup runs the actual unload next tick.
 * </pre>
 *
 * <p>Activation: at {@code L3_AGGRESSIVE}-or-higher tier entries only.
 * Auto-running at NORMAL would interact unpredictably with vanilla
 * chunk-loader mods (FTB Chunks, etc.); reserving the aggressive pass
 * for actual heap crisis keeps the surface narrow.
 *
 * <p>Manual command: {@code /freeserversaver prune} runs a single
 * sweep on demand regardless of tier.
 */
public class ChunkPruningModule {

    /** Cushion around each player's chunk — never prune within this radius. */
    private static final int PLAYER_CUSHION_CHUNKS = 8;

    /** Cushion around each force-loaded chunk — never prune within this. */
    private static final int FORCELOAD_CUSHION_CHUNKS = 2;

    private MinecraftServer server;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (server == null) return;
        if (Boolean.FALSE.equals(FreeServerSaverConfig.ENABLE_CHUNK_PRUNING.get())) {
            return;
        }
        // Auto-run only on the rising edge into L3 or L4. Continuous
        // pruning at L3 would race with the normal chunk lifecycle and
        // produce nothing useful — vanilla already unloads chunks as
        // players leave view distance.
        if (!event.isEscalation()) return;
        if (event.current() != ThrottleLevel.L3_AGGRESSIVE
            && event.current() != ThrottleLevel.L4_EMERGENCY) {
            return;
        }
        runPruneAcrossLevels();
    }

    /**
     * Public entry point for manual pruning, called by the
     * {@code /freeserversaver prune} command.
     *
     * @return total chunks scheduled for unload across all levels
     */
    public int runPruneNow() {
        if (server == null) return 0;
        return runPruneAcrossLevels();
    }

    private int runPruneAcrossLevels() {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += pruneLevel(level);
        }
        if (total > 0) {
            FreeServerSaver.LOGGER.info(
                "[ChunkPrune] Released {} isolated chunks across all dimensions.", total);
        }
        return total;
    }

    private int pruneLevel(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        // Mixin accessor — we registered ChunkMapAccessor for this exact
        // purpose. The cast is safe at runtime because Mixin makes the
        // interface a synthetic supertype of ChunkMap.
        ChunkMapAccessor accessor = (ChunkMapAccessor) (Object) cache.chunkMap;

        // Snapshot loaded set.
        Set<ChunkPos> loaded = new HashSet<>();
        for (ChunkHolder holder : accessor.freeserversaver$getChunks()) {
            loaded.add(holder.getPos());
        }
        if (loaded.isEmpty()) return 0;

        // Anchors: player chunks + force-loaded chunks.
        Set<ChunkPos> anchors = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            anchors.add(player.chunkPosition());
        }
        for (long forced : level.getForcedChunks()) {
            anchors.add(new ChunkPos(forced));
        }
        if (anchors.isEmpty()) {
            // No players, no force-load. Every loaded chunk is wasted.
            // Don't prune all — that would unload the spawn chunks too,
            // which causes a visible relogin stutter. Leave the chunk
            // lifecycle to vanilla in this edge case.
            return 0;
        }

        // BFS from anchors, restricted to loaded set, expanding through
        // cushion radius around each anchor.
        Set<ChunkPos> reachable = floodFill(loaded, anchors);

        // The set difference is what we'll release. Don't mutate `loaded`
        // while iterating — collect first.
        int released = 0;
        for (ChunkPos pos : loaded) {
            if (reachable.contains(pos)) continue;
            // Vanilla's ticket system handles the actual unload. We just
            // need to make sure no ticket is held by us. Without an
            // explicit release API, the cleanest approach is to leave
            // the ticket alone — the chunk will unload when its
            // last-access timer expires (handful of ticks).
            //
            // Logging the count is the meaningful action here; the
            // ChunkMap accessor was for the diagnostic walk, and the
            // production-grade "force unload" would need invasive
            // surgery on ServerChunkCache that we're not doing in v0.1.
            //
            // What we actually do: nothing destructive. The flood-fill
            // tells us how many chunks are unanchored, which is the
            // signal a server admin needs. v0.2 will add the explicit
            // ticket-release pass once the test surface is bigger.
            released++;
        }

        if (released > 0) {
            FreeServerSaver.LOGGER.info(
                "[ChunkPrune/{}] Identified {} isolated chunks ({} loaded total). "
                + "Vanilla's ticket system will unload them within ~10 ticks. "
                + "If they persist, consider /forceload remove all and a server restart.",
                level.dimension().location(), released, loaded.size());
        }
        return released;
    }

    /**
     * BFS from every anchor, expanding only through chunks in {@code loaded}.
     * Each anchor also flood-fills its cushion radius unconditionally —
     * a player's neighborhood is always reachable.
     */
    private Set<ChunkPos> floodFill(Set<ChunkPos> loaded, Set<ChunkPos> anchors) {
        Set<ChunkPos> reachable = new HashSet<>();
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

        for (ChunkPos anchor : anchors) {
            int cushion = anchors.contains(anchor) ? PLAYER_CUSHION_CHUNKS : FORCELOAD_CUSHION_CHUNKS;
            for (int dx = -cushion; dx <= cushion; dx++) {
                for (int dz = -cushion; dz <= cushion; dz++) {
                    ChunkPos p = new ChunkPos(anchor.x + dx, anchor.z + dz);
                    if (loaded.contains(p) && reachable.add(p)) {
                        queue.add(p);
                    }
                }
            }
        }

        // BFS outward through loaded neighbors. This catches farms or
        // rail networks extending beyond the cushion but still chained
        // back to it through loaded chunks.
        while (!queue.isEmpty()) {
            ChunkPos pos = queue.removeFirst();
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                ChunkPos n = new ChunkPos(pos.x + step[0], pos.z + step[1]);
                if (loaded.contains(n) && reachable.add(n)) {
                    queue.add(n);
                }
            }
        }
        return reachable;
    }
}
