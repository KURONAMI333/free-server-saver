package com.kuronami.heapguardian.modules;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.config.HeapGuardianConfig;
import com.kuronami.heapguardian.monitor.ThrottleLevel;
import com.kuronami.heapguardian.monitor.ThrottleLevelChangedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Reduces the server's view-distance and simulation-distance as heap
 * pressure rises, which causes loaded chunks outside the new radius to
 * naturally unload via vanilla's ticket system.
 *
 * <p>This is an indirect approach compared to ChunkPurge's flood-fill
 * unload, but it has two big advantages on a low-RAM Aternos server:
 * <ol>
 *   <li><strong>No mixins / no access transformers.</strong> The
 *       {@link PlayerList} setters are public API and have been stable
 *       across 1.20–1.21. ChunkPurge's flood fill needs to touch
 *       {@code ChunkMap}'s internal loaded-chunks map, which differs
 *       across MC versions and breaks every time the chunk system
 *       refactors. A view-distance change works the same way on every
 *       version.</li>
 *   <li><strong>Vanilla handles the safety.</strong> Chunks that hold
 *       force-load tickets (FTB Chunks, modpack chunkloaders, etc.) are
 *       kept loaded by the ticket system even with view-distance 0,
 *       because tickets and view-distance are independent. The flood
 *       fill has to reproduce that exception logic manually, which is
 *       where ChunkPurge has historically had bugs with modded
 *       chunkloaders.</li>
 * </ol>
 *
 * <p>The trade-off: this module reduces <em>loaded</em> chunks but
 * doesn't <em>delete</em> chunks from disk. The 4GB-storage cap problem
 * (Aternos's other big complaint) needs a separate chunk-pruning module
 * — slated for Phase 3, see {@code HEAP_GUARDIAN_NOTES.md}.
 *
 * <p>Tier mapping (per-tier values are hard-coded — see
 * {@code HeapGuardianConfig} for the reasoning on why thresholds aren't
 * user-configurable in v0.1):
 * <ul>
 *   <li>{@code NORMAL}, {@code L1_MILD}, {@code L2_HEAVY} → unchanged</li>
 *   <li>{@code L3_AGGRESSIVE} → view 6, sim 4</li>
 *   <li>{@code L4_EMERGENCY} → view 4, sim 3</li>
 * </ul>
 *
 * <p>The "minimum playable" floor is 4 chunks of view (about a player's
 * own loaded chunk plus a one-chunk ring). Anything below that triggers
 * the "I can see the void at the edge of the world" bug.
 */
public class ChunkUnloadModule {

    /** View distance applied at {@link ThrottleLevel#L3_AGGRESSIVE}. */
    private static final int L3_VIEW_DISTANCE = 6;
    private static final int L3_SIMULATION_DISTANCE = 4;

    /** View distance applied at {@link ThrottleLevel#L4_EMERGENCY}. */
    private static final int L4_VIEW_DISTANCE = 4;
    private static final int L4_SIMULATION_DISTANCE = 3;

    private MinecraftServer server;

    /**
     * The view/simulation distance the user configured in server.properties
     * (or set via /tick beforehand). Cached at server-start so we can
     * restore exactly the same value rather than guessing "vanilla = 10".
     */
    private int savedViewDistance;
    private int savedSimulationDistance;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        PlayerList list = server.getPlayerList();
        savedViewDistance = list.getViewDistance();
        savedSimulationDistance = list.getSimulationDistance();
        HeapGuardian.LOGGER.info(
            "ChunkUnloadModule armed (saved view={}, simulation={}).",
            savedViewDistance, savedSimulationDistance);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Restore on shutdown. A user opening their server next session
        // shouldn't find their view-distance silently lowered from what
        // they set in server.properties.
        if (server != null) {
            applyDistances(savedViewDistance, savedSimulationDistance);
        }
        server = null;
    }

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (server == null) {
            return;
        }
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_CHUNK_UNLOAD.get())) {
            return;
        }

        int targetView;
        int targetSim;
        switch (event.current()) {
            case L3_AGGRESSIVE -> {
                targetView = Math.min(L3_VIEW_DISTANCE, savedViewDistance);
                targetSim = Math.min(L3_SIMULATION_DISTANCE, savedSimulationDistance);
            }
            case L4_EMERGENCY -> {
                targetView = Math.min(L4_VIEW_DISTANCE, savedViewDistance);
                targetSim = Math.min(L4_SIMULATION_DISTANCE, savedSimulationDistance);
            }
            default -> {
                // NORMAL / L1 / L2 — restore user values. RandomTickModule
                // and SpawnThrottleModule have already taken effect at the
                // lower tiers; chunk unload is reserved for actual crisis.
                targetView = savedViewDistance;
                targetSim = savedSimulationDistance;
            }
        }

        applyDistances(targetView, targetSim);

        if (Boolean.TRUE.equals(HeapGuardianConfig.VERBOSE_LOGGING.get())) {
            HeapGuardian.LOGGER.debug(
                "[ChunkUnload] {} -> view={}, simulation={}",
                event.current(), targetView, targetSim);
        }
    }

    private void applyDistances(int view, int simulation) {
        PlayerList list = server.getPlayerList();
        // The setters broadcast packets to all online players and update
        // their tracked chunk radius. Vanilla handles the unload of chunks
        // that fall outside the new radius — we don't have to.
        list.setViewDistance(view);
        list.setSimulationDistance(simulation);
    }
}
