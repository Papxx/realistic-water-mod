// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.tore.fluidphysics.client.BoatTilt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.11 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.world.entity.vehicle.Boat;
*///?}

/**
 * Tilts the drawn boat to the water particles under it (client only; vanilla's renderer ignores pitch and roll). The
 * boat's own physics are unaffected.
 */
//? if >=1.21.11 {
@Mixin(AbstractBoatRenderer.class)
public abstract class BoatRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;"
            + "Lnet/minecraft/client/renderer/entity/state/BoatRenderState;F)V", at = @At("TAIL"))
    private void fluidphysics$gatherTilt(AbstractBoat boat, BoatRenderState state, float partialTick, CallbackInfo ci) {
        BoatTilt.update(state, boat);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/BoatRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"))
    private void fluidphysics$tiltStart(BoatRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                        CameraRenderState camera, CallbackInfo ci) {
        BoatTilt.apply(state, poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/BoatRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("TAIL"))
    private void fluidphysics$tiltEnd(BoatRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                      CameraRenderState camera, CallbackInfo ci) {
        BoatTilt.end(poseStack);
    }
}
//?} else {
/*@Mixin(BoatRenderer.class)
public abstract class BoatRendererMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/vehicle/Boat;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
    private void fluidphysics$tiltStart(Boat boat, float yaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource buffers, int light, CallbackInfo ci) {
        BoatTilt.update(boat, boat);
        BoatTilt.apply(boat, poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/vehicle/Boat;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("TAIL"))
    private void fluidphysics$tiltEnd(Boat boat, float yaw, float partialTick, PoseStack poseStack,
                                      MultiBufferSource buffers, int light, CallbackInfo ci) {
        BoatTilt.end(poseStack);
    }
}
*///?}
