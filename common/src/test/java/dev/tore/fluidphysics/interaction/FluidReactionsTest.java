// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.WorkerPool;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class FluidReactionsTest {
    private static final WorkerPool POOL = new WorkerPool(1);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    /** Records the reactions of one run. */
    private static final class Recorder implements FluidReactions.Sink {
        final List<String> cooled = new ArrayList<>();
        final List<String> quenched = new ArrayList<>();

        @Override
        public void cooled(int x, int y, int z) {
            cooled.add(x + "," + y + "," + z);
        }

        @Override
        public void quenched(int x, int y, int z, boolean source) {
            quenched.add(x + "," + y + "," + z + (source ? " source" : " flowing"));
        }
    }

    @Test
    void touchingWaterAndLavaParticlesBothReact() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_LAVA, 64, 0f, 0f, 0f);
        sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(5, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        Recorder recorder = new Recorder();

        int marked = new FluidReactions().run(sim, recorder);

        // The lava column at x = 0.875 touches the water column at x = 1.125: 16 pairs.
        assertEquals(32, marked);
        assertEquals(16, recorder.cooled.size());
        assertTrue(recorder.cooled.stream().allMatch(b -> b.startsWith("0,0,")));
        assertEquals(32, sim.removeMarked(null));
        assertEquals(64 * 3 - 32, sim.activeCount());
    }

    @Test
    void particlesReactWithVanillaFluids() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        ChunkVoxelCache voxels = sim.voxels();
        sim.spawn(0, 0, 0, MATERIAL_LAVA, 10, 0f, 0f, 0f);
        sim.spawn(3, 0, 0, MATERIAL_WATER, 10, 0f, 0f, 0f);
        sim.spawn(6, 0, 0, MATERIAL_WATER, 10, 0f, 0f, 0f);
        sim.spawn(9, 0, 0, MATERIAL_SAND, 10, 0f, 0f, 0f);
        // spawn() clears the cache when it starts from nothing, so tag the blocks afterwards.
        voxels.putTag(0, 0, 0, ChunkVoxelCache.TAG_WATER);
        voxels.putTag(3, 0, 0, ChunkVoxelCache.TAG_LAVA | ChunkVoxelCache.TAG_SOURCE);
        voxels.putTag(6, 0, 0, ChunkVoxelCache.TAG_LAVA);
        voxels.putTag(9, 0, 0, ChunkVoxelCache.TAG_LAVA | ChunkVoxelCache.TAG_SOURCE);
        Recorder recorder = new Recorder();

        int marked = new FluidReactions().run(sim, recorder);

        // All 10 lava particles cool in the water; one water particle per lava block quenches it; sand ignores lava.
        assertEquals(12, marked);
        assertEquals(10, recorder.cooled.size());
        assertEquals(List.of("3,0,0 source", "6,0,0 flowing"), recorder.quenched);
    }
}
