package com.kuronami.freeserversaver.mixin.exceptionguard;

import com.kuronami.freeserversaver.exceptionguard.ExceptionGuard;
import com.kuronami.freeserversaver.util.BossDetection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps the {@code Consumer.accept(Entity)} call inside
 * {@link Level#guardEntityTick} so we can catch any {@link Throwable}
 * thrown during entity ticking and quarantine repeat offenders.
 *
 * <h3>Why this site</h3>
 * <p>Vanilla {@code guardEntityTick} already has a try/catch — but its
 * catch builds a {@link net.minecraft.CrashReport} and rethrows as a
 * {@link net.minecraft.ReportedException}, which crashes the server.
 * For an unattended low-RAM host that's the wrong behavior: the offender
 * is one stuck zombie, but the whole server goes down with it.
 *
 * <p>Wrapping the {@code accept} call lets us catch the same exception
 * <em>before</em> vanilla's catch sees it. We hand control back to the
 * caller normally, log the incident, and after {@code N} repeats discard
 * the entity. The vanilla catch never fires.
 *
 * <h3>Why MixinExtras' {@code @WrapOperation}</h3>
 * <p>Compared to {@code @Redirect}, {@code @WrapOperation} composes
 * cleanly with other mods that also wrap the same call site (Neruina,
 * Bug Fix Mod). Each wrapper gets to do its thing — they chain.
 * {@code @Redirect} would conflict and fail at runtime with the latter
 * mods present.
 *
 * <p>The target descriptor is verbatim from Neruina's stonecutter branch,
 * which is known to apply against 1.21.1 through 1.21.8. The {@code remap = false}
 * is intentional: {@code Consumer.accept} is a JDK class, not Minecraft,
 * so the obfuscation mapping shouldn't touch it.
 *
 * <h3>What stays normal</h3>
 * <ul>
 *   <li>Bosses (per {@link BossDetection}) — log only, never discard</li>
 *   <li>When {@link ExceptionGuard#isEnabled()} is false (Neruina/Bug Fix
 *       Mod / Failsafe present), this mixin no-ops by calling
 *       {@code original} unconditionally</li>
 *   <li>Below the 3-hits threshold, the entity continues to tick</li>
 * </ul>
 */
@Mixin(Level.class)
public abstract class LevelGuardEntityTickMixin {

    @WrapOperation(
        method = "guardEntityTick",
        at = @At(value = "INVOKE",
                 target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",
                 remap = false))
    private void fss$catchEntityTick(Consumer<Entity> instance, Object entity,
                                     Operation<Void> original) {
        // If a competing exception-guard mod is loaded the coordinator
        // disables us at startup. Fast-path: no try/catch overhead.
        if (!ExceptionGuard.isEnabled()) {
            original.call(instance, entity);
            return;
        }

        try {
            original.call(instance, entity);
        } catch (Throwable t) {
            // Only quarantine real entities — vanilla can pass weird
            // things through here in edge cases. If not an Entity,
            // we can't quarantine — log via the system logger and
            // swallow. Letting the exception escape would re-trigger
            // the same crash we're trying to prevent.
            if (!(entity instanceof Entity ent)) {
                com.kuronami.freeserversaver.FreeServerSaver.LOGGER.warn(
                    "[ExceptionGuard] Non-Entity passed to guardEntityTick "
                    + "(class={}); swallowing.", entity == null ? "null" : entity.getClass(), t);
                return;
            }

            // Bosses: log via the guard (still helpful), but never discard.
            // Logging happens inside recordEntityException; we ignore its
            // return value (don't discard) when isBoss is true.
            boolean isBoss = (ent instanceof Mob mob) && BossDetection.isBoss(mob);

            String descriptor = ent.getType().toShortString() + " @ "
                + ent.blockPosition().getX() + ","
                + ent.blockPosition().getY() + ","
                + ent.blockPosition().getZ();

            boolean shouldDiscard = ExceptionGuard.recordEntityException(
                ent.getUUID(), descriptor, t);

            if (shouldDiscard && !isBoss) {
                ent.discard();
            }
            // Either way, swallow the exception. The entity either survives
            // for now (under threshold) or is gone (discarded). Either is
            // a stable state for the next tick.
        }
    }
}
