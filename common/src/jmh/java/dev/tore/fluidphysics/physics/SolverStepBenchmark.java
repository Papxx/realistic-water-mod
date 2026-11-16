// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Wall time of one solver tick ({@link PbfSolver#step()}, all substeps) at 4k, 16k and 32k particles (Plan.md §6.5),
 * single-threaded and with the default worker pool, for two scenarios:
 *
 * <ul>
 *   <li>{@code damBreak}: a column of water on an 8 x 8 block footprint in a 16 x 16 box, 150 ticks after it was
 *       released, so the fluid is moving (the same scene as {@code PbfSolverTest} at 16k);</li>
 *   <li>{@code restingPool}: a pool in an 8 x 8 box that has come to rest, where sleeping islands (Step 6b) will
 *       help.</li>
 * </ul>
 *
 * <p>Every invocation restores the same snapshot and runs {@link #STEPS} ticks, so all iterations measure the same
 * stretch of the simulation. The solver's own phase timings over the measured ticks are printed at the end of each
 * trial: the breakdown into grid, neighbours, density, collision and the rest.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class SolverStepBenchmark {
    /** Ticks per invocation: two seconds of game time. */
    static final int STEPS = 40;
    private static final int DAM_BREAK_TICKS = 150;
    private static final int SETTLE_TICKS = 800;

    @Param({"4096", "16384", "32768"})
    public int particles;

    /** Solver threads; 0 means {@link WorkerPool#defaultSize()}. */
    @Param({"1", "0"})
    public int threads;

    @Param({"damBreak", "restingPool"})
    public String scenario;

    private ChunkVoxelCache world;
    private ParticleSoA snapshot;
    private ParticleSoA live;
    private WorkerPool pool;
    private PbfSolver solver;
    private boolean measuring;
    private final long[] phaseNanos = new long[PbfSolver.PHASE_COUNT];
    private long measuredSteps;
    private float meanNeighbors;

    @Setup(Level.Trial)
    public void setUp() {
        int layers = particles / (64 * ParticleSoA.PARTICLES_PER_BLOCK);
        if (layers * 64 * ParticleSoA.PARTICLES_PER_BLOCK != particles) {
            throw new IllegalArgumentException("particles must be a multiple of 4096, got " + particles);
        }
        snapshot = new ParticleSoA(particles);
        int ticks;
        switch (scenario) {
            case "damBreak" -> {
                world = TestWorlds.box(16, 16, 12);
                TestWorlds.fill(snapshot, ParticleSoA.MATERIAL_WATER, 0, 1, 0, 8, 1 + layers, 8);
                ticks = DAM_BREAK_TICKS;
            }
            case "restingPool" -> {
                world = TestWorlds.box(8, 8, layers + 2);
                TestWorlds.fill(snapshot, ParticleSoA.MATERIAL_WATER, 0, 1, 0, 8, 1 + layers, 8);
                ticks = SETTLE_TICKS;
            }
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        // Prepare the snapshot on all cores, whatever the measured thread count.
        try (WorkerPool prepare = new WorkerPool(WorkerPool.defaultSize())) {
            PbfSolver warm = new PbfSolver(snapshot, world, prepare);
            for (int t = 0; t < ticks; t++) {
                warm.step();
            }
        }
        live = new ParticleSoA(particles);
        pool = new WorkerPool(threads == 0 ? WorkerPool.defaultSize() : threads);
        solver = new PbfSolver(live, world, pool);
    }

    @Setup(Level.Iteration)
    public void startIteration(IterationParams params) {
        measuring = params.getType() == IterationType.MEASUREMENT;
    }

    @Setup(Level.Invocation)
    public void restore() {
        live.copyFrom(snapshot);
        solver.invalidateBlockCaches();
    }

    @Benchmark
    @OperationsPerInvocation(STEPS)
    public void step() {
        for (int t = 0; t < STEPS; t++) {
            solver.step();
            if (measuring) {
                for (int phase = 0; phase < PbfSolver.PHASE_COUNT; phase++) {
                    phaseNanos[phase] += solver.lastPhaseNanos(phase);
                }
                measuredSteps++;
            }
        }
        meanNeighbors = solver.lastMeanNeighbors();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "%nPhases (%s, %d particles, %d threads), mean ms per tick over %d measured ticks:",
                scenario, particles, pool.size(), measuredSteps));
        for (int phase = 0; phase < PbfSolver.PHASE_COUNT; phase++) {
            line.append(String.format(Locale.ROOT, " %s %.3f", PbfSolver.phaseName(phase),
                    measuredSteps == 0 ? 0.0 : phaseNanos[phase] / 1.0e6 / measuredSteps));
        }
        line.append(String.format(Locale.ROOT, "; %.1f neighbours per particle", meanNeighbors));
        System.out.println(line);
    }
}
