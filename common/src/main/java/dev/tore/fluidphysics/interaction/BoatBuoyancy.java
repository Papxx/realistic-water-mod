// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Lets boats float on water particles (Plan.md §4.4). Instead of vanilla's single in-water check, the water surface is
 * sampled at the boat's four corners from the particle counts per block; the deeper the corners sit in it, the
 * stronger the lift. With the same formula vanilla uses for water blocks, a boat settles with about two thirds of its
 * height submerged. The particles' mean velocity at the corners drags the boat along, so a stream carries it.
 *
 * <p>Runs wherever the boat is simulated (the server, or the client of the player steering it), against that side's
 * own particles. The boat does not tilt yet: vanilla's boat renderer ignores pitch and roll, so showing the
 * difference between the corners needs a renderer hook per Minecraft version.
 */
public final class BoatBuoyancy {
    /** Half the boat's length and width, at which the corners are sampled. */
    private static final double HALF_LENGTH = 0.65;
    private static final double HALF_WIDTH = 0.55;
    /** Particles that fill one block. Counts in the topmost occupied block give the surface height within it. */
    private static final float FULL_BLOCK = ParticleSoA.PARTICLES_PER_BLOCK;
    /** How fast the boat takes on the particles' mean velocity, per tick and per unit of submersion. */
    private static final double FLOW_DRAG = 0.1;

    private BoatBuoyancy() {
    }

    /**
     * Adds lift and drag from water particles to the boat's velocity, after vanilla's {@code floatBoat} handled
     * gravity and friction for a boat that is not in vanilla water.
     *
     * @return whether the boat touched water particles
     */
    public static boolean apply(Entity boat, FluidSimulation simulation) {
        if (simulation == null || simulation.activeCount() == 0) {
            return false;
        }
        BlockOccupancy occupancy = simulation.occupancy();
        double bottom = boat.getY();
        float height = boat.getBbHeight();
        float yaw = boat.getYRot() * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yaw);
        double forwardZ = Mth.cos(yaw);
        double depthSum = 0;
        double flowX = 0;
        double flowZ = 0;
        int flowSamples = 0;
        for (int corner = 0; corner < 4; corner++) {
            double along = (corner & 1) == 0 ? HALF_LENGTH : -HALF_LENGTH;
            double across = (corner & 2) == 0 ? HALF_WIDTH : -HALF_WIDTH;
            double x = boat.getX() + forwardX * along - forwardZ * across;
            double z = boat.getZ() + forwardZ * along + forwardX * across;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            // The highest block with water from just above the boat down to a block below it.
            for (int by = Mth.floor(bottom + height); by >= Mth.floor(bottom) - 1; by--) {
                int slot = occupancy.slotOf(bx, by, bz);
                int water = slot < 0 ? 0 : occupancy.count(slot, ParticleSoA.MATERIAL_WATER);
                if (water > 0) {
                    double surface = by + Math.min(1f, water / FULL_BLOCK);
                    depthSum += Mth.clamp(surface - bottom, 0.0, height);
                    flowX += occupancy.meanVelX(slot);
                    flowZ += occupancy.meanVelZ(slot);
                    flowSamples++;
                    break;
                }
            }
        }
        if (depthSum <= 0) {
            return false;
        }
        double submerged = depthSum / (4.0 * height);
        Vec3 v = boat.getDeltaMovement();
        // Vanilla's lift for a boat in water: fully balanced with 65 % of the height submerged.
        double vy = (v.y + submerged * (boat.getGravity() / 0.65)) * 0.75;
        double drag = Math.min(1.0, FLOW_DRAG * submerged);
        // Particle velocities are in blocks per second; the boat moves in blocks per tick.
        double vx = v.x + (flowX / flowSamples / 20.0 - v.x) * drag;
        double vz = v.z + (flowZ / flowSamples / 20.0 - v.z) * drag;
        boat.setDeltaMovement(vx, vy, vz);
        return true;
    }
}
