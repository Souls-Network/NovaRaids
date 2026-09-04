package me.unariginal.novaraids.mixin;

import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import me.unariginal.novaraids.utils.CatchEncounterEntityHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catch-phase Pokéballs must pass through stacked/hidden Pokémon that are not
 * the thrower's assigned clone. Cobblemon rejects those hits ("not a wild Pokémon",
 * "in a battle and cannot be caught") before {@code THROWN_POKEBALL_HIT}.
 */
@Mixin(ProjectileEntity.class)
public class CatchPokeBallTargetMixin {
    @Inject(method = "canHit", at = @At("HEAD"), cancellable = true)
    private void novaraids$ignoreWrongCatchTargets(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof EmptyPokeBallEntity ball)) {
            return;
        }
        if (CatchEncounterEntityHelper.shouldPokeballIgnoreEntity(ball.getOwner(), entity)) {
            cir.setReturnValue(false);
        }
    }
}
