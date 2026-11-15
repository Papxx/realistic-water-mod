// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Falling blocks (anvils, dripstone, sand) and particles (Plan.md §4.5): a block that falls fast into particles pushes
 * them down and out, and one that lands on slime bounces back up with 70 % of its speed.
 *
 * <p>Server only: falling blocks move on the server, and the push reaches the clients as an impulse event.
 */
public final class FallingBlockSplash {
    /** Slower falling blocks (blocks per tick) do not splash. */
    private static final double MIN_SPLASH_SPEED = 0.25;
    /** Slime particles in the blocks below that make a falling block bounce. */
    private static final int MIN_SLIME = 8;
    /** Share of the downward speed kept when bouncing off slime. */
    private static final double SLIME_BOUNCE = 0.7;

    private FallingBlockSplash() {
    }

    /**
     * Called at the start of every falling block tick, before it moves.
     *
     * @return whether the falling block bounced off slime (its velocity then needs to be sent to the clients)
     */
    public static boolean beforeTick(Entity falling) {
        if (!(falling.level() instanceof ServerLevel level)) {
            return false;
        }
        FluidSimulation simulation = ServerSimulations.getIfPresent(level);
        Vec3 v = falling.getDeltaMovement();
        if (simulation == null || simulation.activeCount() == 0 || v.y > -0.1) {
            return false;
        }
        BlockOccupancy occupancy = simulation.occupancy();
        double bottom = falling.getBoundingBox().minY;
        int bx = Mth.floor(falling.getX());
        int bz = Mth.floor(falling.getZ());
        int slime = 0;
        int particles = 0;
        // Blocks the bottom face passes this tick, plus a little margin.
        for (int by = Mth.floor(bottom + v.y - 0.25); by <= Mth.floor(bottom); by++) {
            int slot = occupancy.slotOf(bx, by, bz);
            if (slot >= 0) {
                slime += occupancy.count(slot, ParticleSoA.MATERIAL_SLIME);
                particles += occupancy.total(slot);
            }
        }
        double speed = -v.y;
        if (slime >= MIN_SLIME) {
            falling.setDeltaMovement(v.x, speed * SLIME_BOUNCE, v.z);
            ServerSimulations.impulse(level, falling.getX(), bottom, falling.getZ(), 1.5f, (float) (speed * 10.0));
            return true;
        }
        if (particles > 0 && speed > MIN_SPLASH_SPEED) {
            // Centred above the bottom face, so particles below are pushed down and to the sides.
            float force = (float) Math.min(20.0, speed * 20.0 * 1.2);
            ServerSimulations.impulse(level, falling.getX(), bottom + 0.5, falling.getZ(), 1.0f + (float) speed, force);
        }
        return false;
    }
}
