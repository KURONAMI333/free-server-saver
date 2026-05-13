package com.kuronami.heapguardian.command;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Diagnostic snapshot of chunk state across every loaded dimension.
 * Invoked by {@code /heapguardian inspect chunks}.
 *
 * <p>This is the read-only sibling of the originally planned "prune"
 * command. True chunk eviction requires touching {@code ChunkMap}'s
 * package-private members, which means either a mixin or an access
 * transformer — both add a non-trivial mod-loader-side risk surface
 * that doesn't pay off in v0.1.
 *
 * <p>For now, {@link com.kuronami.heapguardian.modules.ChunkUnloadModule}'s
 * view-distance approach handles the automatic case (heap pressure
 * causes vanilla to unload chunks via its own ticket system). This
 * inspect command lets an operator see the resulting numbers and
 * decide whether to lean harder on view-distance, add FerriteCore,
 * or pursue manual region pruning out-of-band.
 *
 * <p>Real chunk-file pruning ("delete .mca files for unvisited regions")
 * is destructive enough that it should never be automatic — slated as
 * a Phase 4 manual command guarded by an explicit confirm flag.
 */
public final class ChunkInspectTask {

    public record DimensionStats(
            String dimension,
            int loadedChunks,
            int forcedChunks,
            int playerCount) {
    }

    private ChunkInspectTask() {}

    public static List<DimensionStats> run(MinecraftServer server) {
        List<DimensionStats> results = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            results.add(new DimensionStats(
                level.dimension().location().toString(),
                level.getChunkSource().getLoadedChunksCount(),
                // getForcedChunks returns a LongSet of packed (x,z) chunk
                // coords kept loaded via /forceload or ForcedChunkManager.
                level.getForcedChunks().size(),
                level.players().size()
            ));
        }
        return results;
    }
}
