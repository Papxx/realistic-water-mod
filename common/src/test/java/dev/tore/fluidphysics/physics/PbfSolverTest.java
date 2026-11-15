// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SLIME;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PbfSolverTest {
    /**
     * Plan.md §5 Step 2 target: median wall time of one tick (all substeps) at 16,384 particles. Not reached yet: on
     * a 6-core desktop the median is about 5 ms with 6 threads (about 19 ms on one thread). The test reports whether
     * the target is met; reaching it is part of Plan.md Step 6b.
     */
    private static final double TARGET_MEDIAN_MS_PER_STEP = 3.5;
    /** What the build enforces until the target is reached: a guard against performance regressions. */
    private static final double MAX_MEDIAN_MS_PER_STEP = 8.0;

    @Test
    void sixteenThousandParticlesStepFastAndAllocateNothing() {
        // Dam break: an 8 x 4 x 8 block column of water (16,384 particles) in a 16 x 16 box.
        ParticleSoA particles = new ParticleSoA(ParticleSoA.MAX_PARTICLES);
        ChunkVoxelCache world = TestWorlds.box(16, 16, 8);
        TestWorlds.fill(particles, MATERIAL_WATER, 0, 1, 0, 8, 5, 8);
        assertEquals(16384, particles.activeCount);
        WorkerPool pool = new WorkerPool(WorkerPool.defaultSize());
        PbfSolver solver = new PbfSolver(particles, world, pool);

        // JIT warm-up; the water collapses and spreads meanwhile, so the measured steps see a moving fluid.
        for (int i = 0; i < 150; i++) {
            solver.step();
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] workerIds = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("FluidPhysics-Worker-"))
                .mapToLong(Thread::threadId)
                .toArray();
        long workersBefore = Arrays.stream(threads.getThreadAllocatedBytes(workerIds)).sum();
        long[] nanos = new long[100];
        long callerBefore = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < nanos.length; i++) {
            solver.step();
            nanos[i] = solver.lastStepNanos();
        }
        long callerAllocated = threads.getCurrentThreadAllocatedBytes() - callerBefore;
        long workersAllocated = Arrays.stream(threads.getThreadAllocatedBytes(workerIds)).sum() - workersBefore;
        pool.close();

        Arrays.sort(nanos);
        double medianMs = nanos[nanos.length / 2] / 1.0e6;
        System.out.printf("PbfSolver @ %d particles, %d threads: median %.3f ms, p90 %.3f ms, max %.3f ms per step "
                        + "(Plan.md target %.1f ms: %s); max neighbours %d; allocated in %d steps: %d bytes on the "
                        + "calling thread, %d bytes on %d workers%n",
                particles.activeCount, pool.size(), medianMs, nanos[89] / 1.0e6, nanos[99] / 1.0e6,
                TARGET_MEDIAN_MS_PER_STEP, medianMs < TARGET_MEDIAN_MS_PER_STEP ? "met" : "NOT met",
                solver.lastMaxNeighbors(), nanos.length, callerAllocated, workersAllocated, workerIds.length);

        assertEquals(0L, callerAllocated, "the step loop must not allocate");
        assertEquals(0L, workersAllocated, "the worker threads must not allocate");
        assertTrue(medianMs < MAX_MEDIAN_MS_PER_STEP,
                "median step time " + medianMs + " ms exceeds " + MAX_MEDIAN_MS_PER_STEP + " ms");
        assertAllInsideBox(particles, 16, 16);
    }

    @Test
    void damBreakSpreadsAndStaysInsideTheBox() {
        ParticleSoA particles = new ParticleSoA(8192);
        TestWorlds.fill(particles, MATERIAL_WATER, 0, 1, 0, 4, 5, 4);
        PbfSolver solver = new PbfSolver(particles, TestWorlds.box(12, 12, 6));
        double startHeight = meanY(particles);

        for (int i = 0; i < 300; i++) {
            solver.step();
        }

        assertAllInsideBox(particles, 12, 12);
        double maxX = 0;
        for (int i = 0; i < particles.activeCount; i++) {
            maxX = Math.max(maxX, particles.posX[i]);
        }
        System.out.printf("Dam break: mean height %.2f -> %.2f, front at x = %.2f%n",
                startHeight, meanY(particles), maxX);
        assertTrue(meanY(particles) < startHeight - 0.8, "water column did not collapse");
        assertTrue(maxX > 9.0, "water did not spread across the floor");
    }

    @Test
    void restingPoolsKeepTheirVolumeAndFallAsleep() {
        // Pools filled exactly to depth: they must neither collapse nor keep ringing, since condensation needs
        // resting particles. Particles sit on the floor (y = 1), so an uncompressed pool of depth d has its mean
        // particle height at 1 + d/2 - 0.125.
        for (int depth : new int[] {1, 3, 6}) {
            ParticleSoA particles = new ParticleSoA(8192);
            TestWorlds.fill(particles, MATERIAL_WATER, 0, 1, 0, 4, 1 + depth, 4);
            PbfSolver solver = new PbfSolver(particles, TestWorlds.box(4, 4, depth + 2));

            // Settling takes 15 to 25 seconds; the deepest pool is the slowest.
            for (int i = 0; i < 500; i++) {
                solver.step();
            }

            int sleeping = 0;
            for (int i = 0; i < particles.activeCount; i++) {
                if (particles.sleepTicks[i] > 60) {
                    sleeping++;
                }
            }
            double volume = (meanY(particles) - 1.0) / (depth / 2.0 - 0.125);
            System.out.printf("Resting pool, depth %d: volume %.0f %%, %d of %d particles asleep%n",
                    depth, volume * 100, sleeping, particles.activeCount);
            assertAllInsideBox(particles, 4, 4);
            assertTrue(volume > 0.85 && volume < 1.1, "depth " + depth + " pool volume " + volume);
            assertTrue(sleeping >= particles.activeCount * 0.95,
                    "depth " + depth + ": only " + sleeping + " particles came to rest");
        }
    }

    @Test
    void pouredSandPilesUpWherePouredWaterFlowsFlat() {
        double sandTop = pourAndMeasureTop(MATERIAL_SAND);
        double waterTop = pourAndMeasureTop(MATERIAL_WATER);
        System.out.printf("24 blocks poured from 8 blocks up: sand pile %.2f high, water %.2f high%n",
                sandTop, waterTop);
        assertTrue(sandTop > 0.9, "sand did not pile up (top " + sandTop + ")");
        assertTrue(waterTop < 0.3, "water did not flow flat (top " + waterTop + ")");
    }

    @Test
    void grainRestsOnAGentleSlopeAndSlidesOffASteepOne() {
        // tan(36.9 deg) = 0.75 is the friction angle of sand.
        assertTrue(grainStaysOnTop(30), "grain slid off a 30 degree contact");
        assertTrue(!grainStaysOnTop(45), "grain held on a 45 degree contact");
    }

    @Test
    void slimeHoldsTogetherBetterThanWater() {
        double slimeSpread = dropBlobAndMeasureSpread(MATERIAL_SLIME);
        double waterSpread = dropBlobAndMeasureSpread(MATERIAL_WATER);
        System.out.printf("Blob of 2 x 2 x 2 blocks dropped from 4 blocks: slime spread %.2f, water spread %.2f%n",
                slimeSpread, waterSpread);
        assertTrue(slimeSpread < waterSpread, "slime spread as far as water");
    }

    @Test
    void particleRestsOnTopOfABottomSlab() {
        ParticleSoA particles = new ParticleSoA(16);
        ChunkVoxelCache world = new ChunkVoxelCache();
        long bottomSlab = 0xFFFF_FFFFL; // sub-voxel layers 0 and 1
        world.putMask(0, 0, 0, bottomSlab);
        particles.add(0.5f, 3.0f, 0.5f, 0f, 0f, 0f, MATERIAL_WATER);
        PbfSolver solver = new PbfSolver(particles, world);

        for (int i = 0; i < 100; i++) {
            solver.step();
        }

        assertEquals(0.5f, particles.posY[0], 0.01f, "particle should rest on the slab surface");
    }

    @Test
    void fastParticleDoesNotTunnelThroughAThinFloor() {
        // One sub-voxel thick floor (like a trapdoor), hit at the speed limit.
        ParticleSoA particles = new ParticleSoA(16);
        ChunkVoxelCache world = new ChunkVoxelCache();
        long trapdoor = 0xFFFFL; // sub-voxel layer 0
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.putMask(x, 0, z, trapdoor);
            }
        }
        particles.add(0.4f, 30.0f, 0.6f, 0f, -PbfSolver.MAX_SPEED, 0f, MATERIAL_SAND);
        PbfSolver solver = new PbfSolver(particles, world);

        for (int i = 0; i < 200; i++) {
            solver.step();
        }

        assertEquals(0.25f, particles.posY[0], 0.01f, "particle should rest on the trapdoor");
    }

    /** Pours 24 blocks of particles from 8 blocks up, one block every 15 ticks, and returns the pile height. */
    private static double pourAndMeasureTop(byte material) {
        ParticleSoA particles = new ParticleSoA(4096);
        PbfSolver solver = new PbfSolver(particles, TestWorlds.box(16, 16, 10));
        for (int b = 0; b < 24; b++) {
            particles.spawnBlock(7 + (b & 1), 9, 7 + ((b >> 1) & 1), material, 64, 0f, 0f, 0f);
            for (int i = 0; i < 15; i++) {
                solver.step();
            }
        }
        for (int i = 0; i < 300; i++) {
            solver.step();
        }
        assertAllInsideBox(particles, 16, 16);
        // Height above the floor (floor surface at y = 1), ignoring a few stray particles on top.
        float[] heights = Arrays.copyOf(particles.posY, particles.activeCount);
        Arrays.sort(heights);
        return heights[(int) (heights.length * 0.99)] - 1.0;
    }

    /** One grain resting on another at {@code degrees} from vertical; whether it is still on top after 5 seconds. */
    private static boolean grainStaysOnTop(double degrees) {
        ParticleSoA particles = new ParticleSoA(4);
        double angle = Math.toRadians(degrees);
        particles.add(3f, 1.001f, 3f, 0f, 0f, 0f, MATERIAL_SAND);
        particles.add((float) (3 + 0.25 * Math.sin(angle)), (float) (1.001 + 0.25 * Math.cos(angle)), 3f,
                0f, 0f, 0f, MATERIAL_SAND);
        PbfSolver solver = new PbfSolver(particles, TestWorlds.box(6, 6, 2));
        for (int i = 0; i < 100; i++) {
            solver.step();
        }
        return particles.posY[1] > 1.1f;
    }

    private static double dropBlobAndMeasureSpread(byte material) {
        ParticleSoA particles = new ParticleSoA(1024);
        TestWorlds.fill(particles, material, 7, 5, 7, 9, 7, 9);
        PbfSolver solver = new PbfSolver(particles, TestWorlds.box(16, 16, 8));
        for (int i = 0; i < 200; i++) {
            solver.step();
        }
        assertAllInsideBox(particles, 16, 16);
        // Root mean square horizontal distance from the drop point.
        double sum = 0;
        for (int i = 0; i < particles.activeCount; i++) {
            double dx = particles.posX[i] - 8.0;
            double dz = particles.posZ[i] - 8.0;
            sum += dx * dx + dz * dz;
        }
        return Math.sqrt(sum / particles.activeCount);
    }

    private static double meanY(ParticleSoA particles) {
        double sum = 0;
        for (int i = 0; i < particles.activeCount; i++) {
            sum += particles.posY[i];
        }
        return sum / particles.activeCount;
    }

    /** Every particle is finite and inside the box built by {@link TestWorlds#box}. */
    private static void assertAllInsideBox(ParticleSoA particles, int width, int depth) {
        for (int i = 0; i < particles.activeCount; i++) {
            float x = particles.posX[i];
            float y = particles.posY[i];
            float z = particles.posZ[i];
            boolean inside = x >= 0f && x <= width && z >= 0f && z <= depth && y >= 1f;
            assertTrue(inside && Float.isFinite(particles.velX[i] + particles.velY[i] + particles.velZ[i]),
                    "particle " + i + " escaped: (" + x + ", " + y + ", " + z + ")");
        }
    }
}
