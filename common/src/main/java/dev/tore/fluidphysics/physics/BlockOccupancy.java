// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import net.minecraft.core.BlockPos;

/**
 * Particle counts per block: how many particles of each material are in a block, how many of them rest, and their
 * mean velocity. Built from a {@link ParticleSoA} in one pass, so entities, items and the condensation scan can ask
 * about a block in O(1) instead of looping over all particles (Plan.md §6.3.6).
 *
 * <p>Every block that holds a particle gets a slot ({@code 0..size()-1}); blocks without particles have none. The
 * arrays grow when a build needs more slots and are reused afterwards, so rebuilding allocates nothing once they are
 * large enough.
 */
public final class BlockOccupancy {
    private static final int MATERIALS = ParticleSoA.MATERIAL_COUNT;

    /** Packed block position to slot; -1 if the block holds no particle. */
    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private long[] keys = new long[256];
    /** Particles per slot and material, at {@code slot * MATERIAL_COUNT + material}. */
    private int[] counts = new int[256 * MATERIALS];
    /** Resting particles per slot and material, same layout. */
    private int[] resting = new int[256 * MATERIALS];
    /** Sum of the particles' velocities per slot, in blocks per second. */
    private float[] velX = new float[256];
    private float[] velY = new float[256];
    private float[] velZ = new float[256];
    private int size;
    /** Bounding box of the blocks with particles, in block coordinates (inclusive); empty while {@code size == 0}. */
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public BlockOccupancy() {
        slots.defaultReturnValue(-1);
    }

    /**
     * Counts the particles of {@code p} per block.
     *
     * @param restingTicks particles with more {@link ParticleSoA#sleepTicks} than this count as resting
     */
    public void build(ParticleSoA p, int restingTicks) {
        slots.clear();
        size = 0;
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
        int n = p.activeCount;
        for (int i = 0; i < n; i++) {
            long key = BlockPos.asLong(
                    p.originX + (int) Math.floor(p.posX[i]),
                    p.originY + (int) Math.floor(p.posY[i]),
                    p.originZ + (int) Math.floor(p.posZ[i]));
            int slot = slots.get(key);
            if (slot < 0) {
                slot = newSlot(key);
            }
            int m = slot * MATERIALS + p.material[i];
            counts[m]++;
            if (p.sleepTicks[i] > restingTicks) {
                resting[m]++;
            }
            velX[slot] += p.velX[i];
            velY[slot] += p.velY[i];
            velZ[slot] += p.velZ[i];
        }
    }

    private int newSlot(long key) {
        if (size == keys.length) {
            int grown = keys.length * 2;
            keys = Arrays.copyOf(keys, grown);
            counts = Arrays.copyOf(counts, grown * MATERIALS);
            resting = Arrays.copyOf(resting, grown * MATERIALS);
            velX = Arrays.copyOf(velX, grown);
            velY = Arrays.copyOf(velY, grown);
            velZ = Arrays.copyOf(velZ, grown);
        }
        int slot = size++;
        keys[slot] = key;
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
        Arrays.fill(counts, slot * MATERIALS, slot * MATERIALS + MATERIALS, 0);
        Arrays.fill(resting, slot * MATERIALS, slot * MATERIALS + MATERIALS, 0);
        velX[slot] = 0f;
        velY[slot] = 0f;
        velZ[slot] = 0f;
        slots.put(key, slot);
        return slot;
    }

    /** Number of blocks that hold at least one particle. */
    public int size() {
        return size;
    }

    /**
     * Whether the block box from ({@code x0}, {@code y0}, {@code z0}) to ({@code x1}, {@code y1}, {@code z1})
     * (inclusive) overlaps the bounding box of the blocks with particles. A cheap test before looking up blocks.
     */
    public boolean mayOverlap(int x0, int y0, int z0, int x1, int y1, int z1) {
        return size > 0 && x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ;
    }

    /** Slot of a block, or -1 if it holds no particle. */
    public int slotOf(int x, int y, int z) {
        return slots.get(BlockPos.asLong(x, y, z));
    }

    /** Slot of a packed block position, or -1 if it holds no particle. */
    public int slotOf(long blockKey) {
        return slots.get(blockKey);
    }

    /** Packed block position of a slot. */
    public long key(int slot) {
        return keys[slot];
    }

    public int count(int slot, byte material) {
        return counts[slot * MATERIALS + material];
    }

    public int resting(int slot, byte material) {
        return resting[slot * MATERIALS + material];
    }

    /** All particles in a slot, whatever their material. */
    public int total(int slot) {
        int base = slot * MATERIALS;
        int sum = 0;
        for (int m = 0; m < MATERIALS; m++) {
            sum += counts[base + m];
        }
        return sum;
    }

    /** Particles of {@code material} in a block; 0 for a block without particles. */
    public int count(int x, int y, int z, byte material) {
        int slot = slotOf(x, y, z);
        return slot < 0 ? 0 : counts[slot * MATERIALS + material];
    }

    /** Mean velocity of the particles in a slot, in blocks per second. */
    public float meanVelX(int slot) {
        return velX[slot] / Math.max(1, total(slot));
    }

    public float meanVelY(int slot) {
        return velY[slot] / Math.max(1, total(slot));
    }

    public float meanVelZ(int slot) {
        return velZ[slot] / Math.max(1, total(slot));
    }
}
