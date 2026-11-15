// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.FallingBlockSplash;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Falling blocks splash through particles and bounce off slime (Plan.md §4.5). */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void fluidphysics$splash(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (FallingBlockSplash.beforeTick(self)) {
            // Send the new velocity now instead of with the next regular update, up to 20 ticks later.
            //? if >=1.21.11 {
            self.needsSync = true;
            //?} else {
            /*self.hasImpulse = true;
            *///?}
        }
    }
}
