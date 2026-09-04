package me.unariginal.novaraids.mixin;

import me.unariginal.novaraids.utils.RaidVisibility;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Failsafe: if a catch/raid entity somehow remains tracked for the wrong player,
 * force-stop tracking instead of letting spawn/update packets through.
 * Owner catch clones are never stop-tracked here (force-show path).
 */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class CatchEncounterTrackerMixin {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    public abstract void stopTracking(ServerPlayerEntity player);

    @Inject(
            method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void novaraids$hideCatchEncounters(ServerPlayerEntity player, CallbackInfo ci) {
        if (RaidVisibility.forceShowCatchTo(this.entity, player)) {
            // Let vanilla updatePlayer run so the owner stays tracked.
            return;
        }
        if (RaidVisibility.shouldHideFrom(this.entity, player)) {
            this.stopTracking(player);
            ci.cancel();
        }
    }
}
