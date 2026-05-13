package com.kuronami.freeserversaver.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * On-demand census of which entity types are present on the server.
 * Invoked from {@code /freeserversaver top entities}.
 *
 * <p>Lag spikes are usually trace-able to an entity count problem
 * somewhere — an iron farm runaway, an XP grinder backed up because no
 * one's picking the orbs up, a chunkloader keeping a mob farm hot. The
 * lagspikes log tells you THAT something spiked; this task tells you
 * WHICH ENTITY TYPE is currently the heaviest census-wise, so the
 * operator can decide whether to clean it up.
 *
 * <p>Iterates {@link ServerLevel#getAllEntities()} once per dimension
 * — moderately expensive (proportional to total entity count). Don't
 * call this on a tick path; only from a deliberate command invocation.
 */
public final class EntityCountTask {

    public record TypeCount(String typeId, String dimensionId, int count) {}

    private EntityCountTask() {}

    /**
     * Census every dimension and return the top {@code topN} entity
     * types by combined count across all dimensions.
     */
    public static List<TypeCount> run(MinecraftServer server, int topN) {
        // Two-level aggregation: per-(type × dimension) so the operator
        // can see "100 zombies in the_nether vs 30 zombies in overworld"
        // rather than just "130 zombies somewhere." The dimension scope
        // is what actually matters for cleanup decisions.
        Map<String, Map<String, Integer>> byTypeByDim = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                EntityType<?> type = e.getType();
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                byTypeByDim
                    .computeIfAbsent(id, k -> new HashMap<>())
                    .merge(dim, 1, Integer::sum);
            }
        }

        // Flatten and sort. We want top types overall, but report the
        // per-dimension breakdown for the winners.
        List<TypeCount> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> typeEntry : byTypeByDim.entrySet()) {
            for (Map.Entry<String, Integer> dimEntry : typeEntry.getValue().entrySet()) {
                result.add(new TypeCount(typeEntry.getKey(), dimEntry.getKey(), dimEntry.getValue()));
            }
        }
        result.sort(Comparator.comparingInt(TypeCount::count).reversed());

        if (result.size() > topN) {
            return result.subList(0, topN);
        }
        return result;
    }
}
