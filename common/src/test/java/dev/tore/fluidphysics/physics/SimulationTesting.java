// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

/** Opens package-private test hooks of {@link FluidSimulation} to tests in other packages. */
public final class SimulationTesting {
    private SimulationTesting() {
    }

    /**
     * One tick without a level ({@link FluidSimulation#advance}): the voxel cache must already hold the world.
     * Particles below {@code removeBelowY} are removed.
     */
    public static void advance(FluidSimulation simulation, int removeBelowY) {
        simulation.advance(removeBelowY);
    }
}
