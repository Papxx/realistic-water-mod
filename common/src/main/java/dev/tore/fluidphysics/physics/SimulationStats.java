// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

/**
 * Rolling statistics of one {@link FluidSimulation} over its last {@link #WINDOW} simulated ticks (Plan.md §6.5): wall
 * time of the whole tick and of its parts (voxel cache, solver phases, interactions), and heap bytes allocated on the
 * ticking thread and on the solver's worker threads. Ticks without particles are not recorded.
 *
 * <p>Recording allocates nothing. Read the statistics from the thread that ticks the simulation (commands on the server
 * thread, client commands on the render thread).
 */
public final class SimulationStats {
    /** Simulated ticks covered: 10 seconds at 20 ticks per second. */
    public static final int WINDOW = 200;

    private final RollingSamples tick = new RollingSamples(WINDOW);
    private final RollingSamples step = new RollingSamples(WINDOW);
    private final RollingSamples voxels = new RollingSamples(WINDOW);
    private final RollingSamples interactions = new RollingSamples(WINDOW);
    private final RollingSamples[] phases = new RollingSamples[PbfSolver.PHASE_COUNT];
    private final RollingSamples allocated = new RollingSamples(WINDOW);
    private final RollingSamples workerAllocated = new RollingSamples(WINDOW);
    private float lastMeanNeighbors;
    private int lastMaxNeighbors;

    public SimulationStats() {
        for (int p = 0; p < phases.length; p++) {
            phases[p] = new RollingSamples(WINDOW);
        }
    }

    /**
     * Records one simulated tick.
     *
     * @param tickNanos       wall time of the whole tick so far
     * @param voxelNanos      part of it spent filling the voxel cache
     * @param solver          the solver that just stepped; its last step's timings are copied
     * @param allocatedBytes  bytes allocated by the ticking thread during the tick
     * @param workerBytes     bytes allocated by the solver's worker threads during the tick
     */
    void record(long tickNanos, long voxelNanos, PbfSolver solver, long allocatedBytes, long workerBytes) {
        tick.add(tickNanos);
        step.add(solver.lastStepNanos());
        voxels.add(voxelNanos);
        interactions.add(0L);
        for (int p = 0; p < phases.length; p++) {
            phases[p].add(solver.lastPhaseNanos(p));
        }
        allocated.add(allocatedBytes);
        workerAllocated.add(workerBytes);
        lastMeanNeighbors = solver.lastMeanNeighbors();
        lastMaxNeighbors = solver.lastMaxNeighbors();
    }

    /**
     * Adds work done for this simulation after its tick (the server's interactions: reactions, cauldrons,
     * condensation) to the last recorded tick. Only call it in a tick that recorded a sample.
     */
    public void addInteractions(long nanos, long allocatedBytes) {
        tick.addToLast(nanos);
        interactions.addToLast(nanos);
        allocated.addToLast(allocatedBytes);
    }

    /** Forgets all samples. */
    public void reset() {
        tick.clear();
        step.clear();
        voxels.clear();
        interactions.clear();
        for (RollingSamples phase : phases) {
            phase.clear();
        }
        allocated.clear();
        workerAllocated.clear();
        lastMeanNeighbors = 0f;
        lastMaxNeighbors = 0;
    }

    /** Recorded ticks, at most {@link #WINDOW}. */
    public int samples() {
        return tick.count();
    }

    /** Wall time of everything done for the simulation in a tick (voxels, solver, void removal, interactions). */
    public RollingSamples tick() {
        return tick;
    }

    /** Wall time of the solver step ({@link PbfSolver#step()}). */
    public RollingSamples step() {
        return step;
    }

    /** Wall time of filling the voxel cache around the particles. */
    public RollingSamples voxels() {
        return voxels;
    }

    /** Wall time of the server's interactions (0 on the client). */
    public RollingSamples interactions() {
        return interactions;
    }

    /** Wall time of one solver phase ({@code PbfSolver.PHASE_*}). */
    public RollingSamples phase(int phase) {
        return phases[phase];
    }

    /** Bytes allocated by the ticking thread; 0 if {@link AllocationMeter#isSupported()} is false. */
    public RollingSamples allocated() {
        return allocated;
    }

    /** Bytes allocated by the solver's worker threads. */
    public RollingSamples workerAllocated() {
        return workerAllocated;
    }

    /** Mean neighbours per particle in the last substep. */
    public float lastMeanNeighbors() {
        return lastMeanNeighbors;
    }

    /** Most neighbours of any particle in the last substep. */
    public int lastMaxNeighbors() {
        return lastMaxNeighbors;
    }
}
