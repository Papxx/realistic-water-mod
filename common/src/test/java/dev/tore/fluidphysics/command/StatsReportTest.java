// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.network.NetworkStats;
import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.PbfSolver;
import dev.tore.fluidphysics.physics.SimulationTesting;
import dev.tore.fluidphysics.physics.WorkerPool;
import org.junit.jupiter.api.Test;

class StatsReportTest {
    @Test
    void formatsUnits() {
        assertEquals("1.50", StatsReport.millis(1_500_000L));
        assertEquals("0.00", StatsReport.millis(0L));
        assertEquals("512 B", StatsReport.bytes(512L));
        assertEquals("1.5 KiB", StatsReport.bytes(1536L));
        assertEquals("2.0 MiB", StatsReport.bytes(2L * 1024 * 1024));
    }

    @Test
    void simulationLinesCoverEveryTopic() {
        WorkerPool pool = new WorkerPool(1);
        try {
            FluidSimulation sim = new FluidSimulation(pool, 1000);
            sim.voxels().putMask(0, -1, 0, ChunkVoxelCache.FULL);
            sim.spawn(0, 0, 0, ParticleSoA.MATERIAL_WATER, 64, 0f, 0f, 0f);
            SimulationTesting.advance(sim, -1000);
            SimulationTesting.advance(sim, -1000);

            String text = StatsReport.simulationLines(sim);
            assertTrue(text.contains("water 64, lava 0, slime 0, sand 0"), text);
            assertTrue(text.contains("over the last 2 ticks"), text);
            for (int phase = 0; phase < PbfSolver.PHASE_COUNT; phase++) {
                assertTrue(text.contains(PbfSolver.phaseName(phase) + " "), text);
            }
            assertTrue(text.contains("neighbours: "), text);
            assertTrue(text.contains("allocated per tick: "), text);
            assertTrue(text.contains("voxel cache: "), text);
            assertTrue(!text.contains("interactions"), "the client has no interactions: " + text);
            assertEquals(6, text.lines().count(), text);
        } finally {
            pool.close();
        }
    }

    @Test
    void networkLineNamesEveryKind() {
        String text = StatsReport.networkLine(new NetworkStats());
        for (int kind = 0; kind < NetworkStats.KIND_COUNT; kind++) {
            assertTrue(text.contains(NetworkStats.kindName(kind) + " 0.0"), text);
        }
        assertTrue(text.contains("0 packets (about 0 B) since the server started"), text);
    }
}
