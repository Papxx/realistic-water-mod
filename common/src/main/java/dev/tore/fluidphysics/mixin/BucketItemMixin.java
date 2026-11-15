// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.ParticlePickup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.2 {
import net.minecraft.world.InteractionResult;
//?} else {
/*import net.minecraft.world.InteractionResultHolder;
*///?}

/**
 * An empty vanilla bucket picks up 64 water, lava or slime particles before it looks for a vanilla fluid block
 * (Plan.md §4.1). Without enough particles in reach, vanilla behaviour is unchanged.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    //? if >=1.21.2 {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void fluidphysics$fillFromParticles(Level level, Player player, InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack result = ParticlePickup.tryFill(level, player, player.getItemInHand(hand));
        if (result != null) {
            cir.setReturnValue(InteractionResult.SUCCESS.heldItemTransformedTo(result));
        }
    }
    //?} else {
    /*@Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void fluidphysics$fillFromParticles(Level level, Player player, InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack result = ParticlePickup.tryFill(level, player, player.getItemInHand(hand));
        if (result != null) {
            cir.setReturnValue(InteractionResultHolder.sidedSuccess(result, level.isClientSide()));
        }
    }
    *///?}
}
