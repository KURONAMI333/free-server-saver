package com.kuronami.freeserversaver.mixin.exceptionguard;

import com.kuronami.freeserversaver.exceptionguard.ExceptionGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps {@code BlockEntityTicker.tick(...)} inside the
 * {@code LevelChunk$BoundTickingBlockEntity#tick()} bridge so we can
 * catch exceptions from broken block entity tick logic.
 *
 * <h3>Why the inner-class target</h3>
 * <p>The actual {@code BlockEntityTicker} is a functional interface so
 * we can't mixin into it directly. Vanilla calls it from
 * {@code LevelChunk}'s {@code BoundTickingBlockEntity} adapter, which
 * is the stable call-site that has existed in every MC version since
 * the chunk system was rewritten. Neruina's stonecutter branch targets
 * the same site.
 *
 * <h3>When the threshold is hit</h3>
 * <p>We call {@link Level#removeBlockEntity(BlockPos)} which removes the
 * block-entity attachment but leaves the block itself in place. The
 * player sees the chest / hopper / furnace still there, but its
 * functionality is gone. Reopening (right-clicking) won't bring it back
 * — the data is gone — but neither will the server crash again. This
 * matches the conservative principle: data loss for the broken object
 * only, not for the surrounding world.
 *
 * <h3>What this DOESN'T do</h3>
 * <ul>
 *   <li>Break the block (no item drop, no neighbor update)</li>
 *   <li>Replace it with a different block</li>
 *   <li>Save the broken NBT for later inspection — we don't have a place
 *       to store it and the operator already has the log line</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private <T extends BlockEntity> void fss$catchBlockEntityTick(
            BlockEntityTicker<T> instance,
            Level level,
            BlockPos pos,
            BlockState state,
            T blockEntity,
            Operation<Void> original) {

        if (!ExceptionGuard.isEnabled()) {
            original.call(instance, level, pos, state, blockEntity);
            return;
        }

        try {
            original.call(instance, level, pos, state, blockEntity);
        } catch (Throwable t) {
            String typeId = blockEntity != null
                ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString()
                : "<null>";
            String descriptor = typeId + " @ "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ();

            boolean shouldRemove = ExceptionGuard.recordBlockEntityException(pos, descriptor, t);

            if (shouldRemove) {
                level.removeBlockEntity(pos);
            }
            // Always swallow — same rationale as the entity-tick mixin.
            // The next tick either continues with the still-broken BE
            // (it'll trip the counter again) or with no BE at all.
        }
    }
}
