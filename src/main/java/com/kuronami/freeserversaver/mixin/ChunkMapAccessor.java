package com.kuronami.freeserversaver.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker mixin that exposes {@link ChunkMap#getChunks()} as a public
 * method on a parallel accessor interface.
 *
 * <p>Vanilla declares {@code getChunks()} as {@code protected}. Heap
 * Guardian's {@code ChunkPruningModule} needs to walk the loaded-chunk
 * set to do its flood-fill, which requires public access. An accessor
 * mixin is the minimum-risk way to expose this — it doesn't change the
 * actual bytecode of {@code getChunks}, it just adds a synthetic public
 * method to {@code ChunkMap} that calls through to the protected one.
 *
 * <p>Compared to a normal mixin that injects into method bodies:
 * <ul>
 *   <li>No runtime behavior change for vanilla code paths</li>
 *   <li>Survives most MC patch updates — the only thing that breaks
 *       it is renaming {@code getChunks()} or removing it</li>
 *   <li>Compatible with other mods doing the same thing (multiple
 *       accessor mixins on the same class compose cleanly)</li>
 * </ul>
 *
 * <p>The method name {@code freeserversaver$getChunks} is prefixed to
 * avoid colliding with vanilla or other mods' accessors of the same
 * underlying field. The {@code $} convention is Mixin's recommended
 * style for mod-private synthetic methods.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getChunks")
    Iterable<ChunkHolder> freeserversaver$getChunks();
}
