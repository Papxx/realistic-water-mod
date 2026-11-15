// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Finds where water and lava meet and picks the particles that react ({@link FluidSimulation#mark}); the caller removes
 * them and turns the reports into blocks.
 *
 * <ul>
 *   <li>A lava particle that touches a water particle cools down: both disappear (the water turns to steam) and the
 *       lava counts towards stone at its block ({@link Sink#cooled}).</li>
 *   <li>A lava particle inside a vanilla water block cools down the same way; the water block stays.</li>
 *   <li>A water particle inside a vanilla lava block turns it into stone like vanilla flowing water would
 *       ({@link Sink#quenched}): obsidian from a source, cobblestone from flowing lava. One water particle per block is
 *       used up.</li>
 * </ul>
 *
 * <p>Vanilla fluids are read from the voxel cache's {@link ChunkVoxelCache#getTag tags}, never from the level. Reuses
 * its tables between runs, so a run allocates nothing once they have grown.
 */
public final class FluidReactions {
    /** Two particles closer than this touch. Neighbouring particles at rest sit about 0.25 blocks apart. */
    static final float CONTACT_DISTANCE = 0.3f;
    private static final float CONTACT2 = CONTACT_DISTANCE * CONTACT_DISTANCE;

    /** Receives the reactions of one {@link #run}. */
    public interface Sink {
        /** One lava particle cooled into stone in block ({@code x}, {@code y}, {@code z}). */
        void cooled(int x, int y, int z);

        /** A water particle reached the vanilla lava in block ({@code x}, {@code y}, {@code z}). */
        void quenched(int x, int y, int z, boolean source);
    }

    /** Quarter-block cell (origin-relative, packed) to the index of one water particle in it. */
    private final Long2IntOpenHashMap waterCells = new Long2IntOpenHashMap();
    /** Vanilla lava blocks already quenched in this run. */
    private final LongOpenHashSet quenched = new LongOpenHashSet();

    public FluidReactions() {
        waterCells.defaultReturnValue(-1);
    }

    /**
     * Marks the particles that react and reports each reaction to {@code sink}.
     *
     * @return the number of particles marked
     */
    public int run(FluidSimulation simulation, Sink sink) {
        ParticleSoA p = simulation.particles();
        if (p == null || p.activeCount == 0) {
            return 0;
        }
        ChunkVoxelCache voxels = simulation.voxels();
        int marked = 0;
        int water = 0;
        int lava = 0;
        quenched.clear();
        for (int i = 0; i < p.activeCount; i++) {
            byte m = p.material[i];
            if (m != ParticleSoA.MATERIAL_WATER && m != ParticleSoA.MATERIAL_LAVA) {
                continue;
            }
            int bx = p.originX + (int) Math.floor(p.posX[i]);
            int by = p.originY + (int) Math.floor(p.posY[i]);
            int bz = p.originZ + (int) Math.floor(p.posZ[i]);
            int tag = voxels.getTag(bx, by, bz);
            if (m == ParticleSoA.MATERIAL_WATER) {
                water++;
                if ((tag & ChunkVoxelCache.TAG_LAVA) != 0 && quenched.add(BlockPos.asLong(bx, by, bz))) {
                    simulation.mark(i);
                    marked++;
                    sink.quenched(bx, by, bz, (tag & ChunkVoxelCache.TAG_SOURCE) != 0);
                }
            } else {
                lava++;
                if ((tag & ChunkVoxelCache.TAG_WATER) != 0) {
                    simulation.mark(i);
                    marked++;
                    sink.cooled(bx, by, bz);
                }
            }
        }
        if (water > 0 && lava > 0) {
            marked += touchingParticles(simulation, p, sink);
        }
        return marked;
    }

    /** Lava particles touching water particles: both are marked, the lava cools. */
    private int touchingParticles(FluidSimulation simulation, ParticleSoA p, Sink sink) {
        waterCells.clear();
        for (int i = 0; i < p.activeCount; i++) {
            if (p.material[i] == ParticleSoA.MATERIAL_WATER && !simulation.isMarked(i)) {
                waterCells.put(cellKey(cell(p.posX[i]), cell(p.posY[i]), cell(p.posZ[i])), i);
            }
        }
        int marked = 0;
        for (int j = 0; j < p.activeCount; j++) {
            if (p.material[j] != ParticleSoA.MATERIAL_LAVA || simulation.isMarked(j)) {
                continue;
            }
            float x = p.posX[j];
            float y = p.posY[j];
            float z = p.posZ[j];
            int cx = cell(x);
            int cy = cell(y);
            int cz = cell(z);
            search:
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int w = waterCells.get(cellKey(cx + dx, cy + dy, cz + dz));
                        if (w < 0 || simulation.isMarked(w)) {
                            continue;
                        }
                        float ex = p.posX[w] - x;
                        float ey = p.posY[w] - y;
                        float ez = p.posZ[w] - z;
                        if (ex * ex + ey * ey + ez * ez < CONTACT2) {
                            simulation.mark(j);
                            simulation.mark(w);
                            marked += 2;
                            sink.cooled(p.originX + (int) Math.floor(x), p.originY + (int) Math.floor(y),
                                    p.originZ + (int) Math.floor(z));
                            break search;
                        }
                    }
                }
            }
        }
        return marked;
    }

    /** Quarter-block cell of an origin-relative coordinate. */
    private static int cell(float v) {
        return (int) Math.floor(v * 4f);
    }

    /** Packs a cell; 21 bits per axis cover ±262,144 blocks around the origin, far more than particles may stray. */
    private static long cellKey(int cx, int cy, int cz) {
        return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0x1FFFFF) << 21) | (cz & 0x1FFFFF);
    }
}
