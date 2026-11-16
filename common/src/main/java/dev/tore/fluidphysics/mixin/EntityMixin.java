// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.EntityContact;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets particles act on every entity once per tick (fire, buoyancy, drag, slime bounce), right where vanilla handles
 * its own fluids: at the end of the base tick, before the entity moves.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void fluidphysics$touchParticles(CallbackInfo ci) {
        EntityContact.afterBaseTick((Entity) (Object) this);
    }
}
