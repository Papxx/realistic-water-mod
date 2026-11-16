// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.EntityContact;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets living entities swim up (jump) and dive (sneak) in water particles, which vanilla does only for water blocks.
 * Runs at the end of {@code aiStep}, where vanilla applies its own swim-up impulse.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow
    protected boolean jumping;

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void fluidphysics$swimInParticles(CallbackInfo ci) {
        EntityContact.swim((LivingEntity) (Object) this, jumping);
    }
}
