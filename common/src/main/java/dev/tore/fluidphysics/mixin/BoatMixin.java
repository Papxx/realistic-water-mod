// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.BoatBuoyancy;
import dev.tore.fluidphysics.interaction.Simulations;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
//?} else {
/*import net.minecraft.world.entity.vehicle.Boat;
*///?}

/**
 * Boats float on water particles (Plan.md §4.4). Runs after vanilla's {@code floatBoat}, only when vanilla sees no
 * water under the boat (in the air or on land), and adds the lift of the particles at the boat's corners.
 */
//? if >=1.21.11 {
@Mixin(AbstractBoat.class)
//?} else {
/*@Mixin(Boat.class)
*///?}
public abstract class BoatMixin {
    //? if >=1.21.11 {
    @Shadow
    private AbstractBoat.Status status;
    //?} else {
    /*@Shadow
    private Boat.Status status;
    *///?}

    @Inject(method = "floatBoat", at = @At("TAIL"))
    private void fluidphysics$floatOnParticles(CallbackInfo ci) {
        if (fluidphysics$outOfVanillaWater()) {
            Entity boat = (Entity) (Object) this;
            BoatBuoyancy.apply(boat, Simulations.forLevel(boat.level()));
        }
    }

    private boolean fluidphysics$outOfVanillaWater() {
        //? if >=1.21.11 {
        return status == AbstractBoat.Status.IN_AIR || status == AbstractBoat.Status.ON_LAND;
        //?} else {
        /*return status == Boat.Status.IN_AIR || status == Boat.Status.ON_LAND;
        *///?}
    }
}
