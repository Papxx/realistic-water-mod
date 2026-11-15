// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpatialHashGridTest {
    @Test
    void countingSortPlacesEveryParticleOnceInItsBucket() {
        int n = 5000;
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        Random random = new Random(1);
        for (int i = 0; i < n; i++) {
            x[i] = random.nextFloat() * 40f - 20f;
            y[i] = random.nextFloat() * 10f - 5f;
            z[i] = random.nextFloat() * 40f - 20f;
        }
        SpatialHashGrid grid = new SpatialHashGrid(n);
        grid.build(x, y, z, n);

        boolean[] seen = new boolean[n];
        int total = 0;
        for (int b = 0; b < SpatialHashGrid.HASH_SIZE; b++) {
            for (int s = grid.cellStart[b]; s < grid.cellStart[b] + grid.cellCount[b]; s++) {
                int i = grid.sortedIndices[s];
                assertTrue(!seen[i], "particle " + i + " sorted twice");
                seen[i] = true;
                int cx = SpatialHashGrid.cellCoord(x[i]);
                int cy = SpatialHashGrid.cellCoord(y[i]);
                int cz = SpatialHashGrid.cellCoord(z[i]);
                assertEquals(b, grid.hashCell(cx, cy, cz));
                assertEquals(SpatialHashGrid.packCell(cx, cy, cz), grid.sortedCellKey[s]);
                total++;
            }
        }
        assertEquals(n, total);
    }

    @Test
    void neighbourListsMatchBruteForce() {
        // Random particles around the origin, including negative coordinates and cell borders.
        ParticleSoA particles = new ParticleSoA(1500);
        Random random = new Random(7);
        for (int i = 0; i < 1500; i++) {
            particles.add(random.nextFloat() * 3f - 1.5f, random.nextFloat() * 3f - 1.5f,
                    random.nextFloat() * 3f - 1.5f, 0f, 0f, 0f, ParticleSoA.MATERIAL_WATER);
        }
        PbfSolver solver = new PbfSolver(particles, new ChunkVoxelCache());
        solver.buildNeighborsAtCurrentPositions();
        assertTrue(solver.lastMaxNeighbors() < PbfSolver.MAX_NEIGHBORS, "test density too high for the cap");

        int[] order = solver.grid().sortedIndices;
        int n = particles.activeCount;
        for (int slot = 0; slot < n; slot++) {
            int i = order[slot];
            Set<Integer> found = new HashSet<>();
            for (int c = 0; c < solver.neighborCountOfSlot(slot); c++) {
                assertTrue(found.add(order[solver.neighborSlot(slot, c)]), "duplicate neighbour of " + i);
            }
            Set<Integer> expected = new HashSet<>();
            for (int j = 0; j < n; j++) {
                float dx = particles.posX[i] - particles.posX[j];
                float dy = particles.posY[i] - particles.posY[j];
                float dz = particles.posZ[i] - particles.posZ[j];
                if (j != i && dx * dx + dy * dy + dz * dz < PbfSolver.H2) {
                    expected.add(j);
                }
            }
            assertEquals(expected, found, "neighbours of particle " + i);
        }
    }
}
