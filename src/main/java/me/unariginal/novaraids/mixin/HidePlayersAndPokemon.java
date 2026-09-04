package me.unariginal.novaraids.mixin;

import me.unariginal.novaraids.utils.RaidVisibility;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Yarn {@code canBeSpectated} / official {@code broadcastToPlayer} is the vanilla
 * per-player tracking gate in 1.21 ({@code ChunkMap$TrackedEntity#updatePlayer}).
 * Returning false stops the entity from being sent to that client.
 *
 * Explicit force-show for catch owners prevents inverted hide when ownership
 * resolution briefly disagrees between tags and the clones map.
 */
@Mixin(Entity.class)
public class HidePlayersAndPokemon {
    @Inject(method = "canBeSpectated", at = @At("HEAD"), cancellable = true)
    private void novaraids$canBeSpectated(ServerPlayerEntity spectator, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (RaidVisibility.forceShowCatchTo(self, spectator)) {
            cir.setReturnValue(true);
            return;
        }
        if (RaidVisibility.shouldHideFrom(self, spectator)) {
            cir.setReturnValue(false);
        }
    }
}
