// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.tore.fluidphysics.interaction.BoatBuoyancy;
import dev.tore.fluidphysics.interaction.Simulations;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

/**
 * Draws boats tilted to the water particles under them. Both Minecraft versions' boat renderers call {@link #apply}
 * before drawing and {@link #end} afterwards; the tilt is smoothed over frames so the 1/64-block steps of the particle
 * counts do not make the boat shiver. Render thread only.
 */
public final class BoatTilt {
    /** Share of the remaining difference closed per call (a few calls per frame at most). */
    private static final float SMOOTHING = 0.15f;
    /** Pivot height above the boat's feet, about the middle of its hull. */
    private static final float PIVOT_Y = 0.4f;

    /** Smoothed tilt per boat (or per render state), as axis x, axis z, angle. */
    private static final WeakHashMap<Object, float[]> TILTS = new WeakHashMap<>();
    private static final float[] RAW = new float[3];
    private static final Quaternionf ROTATION = new Quaternionf();

    private BoatTilt() {
    }

    /** Computes the tilt of {@code boat} and remembers it under {@code key}; call when the frame's data is gathered. */
    public static void update(Object key, Entity boat) {
        float[] tilt = TILTS.computeIfAbsent(key, k -> new float[3]);
        if (BoatBuoyancy.tilt(boat, Simulations.forLevel(boat.level()), RAW)) {
            if (tilt[2] == 0f) {
                tilt[0] = RAW[0];
                tilt[1] = RAW[1];
            } else {
                // Blend the axes too, so turning the boat does not snap the tilt.
                tilt[0] += (RAW[0] - tilt[0]) * SMOOTHING;
                tilt[1] += (RAW[1] - tilt[1]) * SMOOTHING;
            }
            tilt[2] += (RAW[2] - tilt[2]) * SMOOTHING;
        } else {
            tilt[2] *= 1f - SMOOTHING;
            if (tilt[2] < 1e-3f) {
                tilt[2] = 0f;
            }
        }
    }

    /** Pushes the pose and rotates it by the tilt remembered under {@code key}; pair with {@link #end}. */
    public static void apply(Object key, PoseStack poseStack) {
        poseStack.pushPose();
        float[] tilt = TILTS.get(key);
        if (tilt != null && tilt[2] != 0f) {
            poseStack.translate(0f, PIVOT_Y, 0f);
            poseStack.mulPose(ROTATION.rotationAxis(tilt[2], tilt[0], 0f, tilt[1]));
            poseStack.translate(0f, -PIVOT_Y, 0f);
        }
    }

    public static void end(PoseStack poseStack) {
        poseStack.popPose();
    }
}
