// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

/**
 * Chunk columns packed into a {@code long}, in the same layout as {@code ChunkPos.asLong(x, z)} (x in the low 32 bits,
 * z in the high 32 bits), so the keys can be passed to Minecraft as they are. Kept separate because loading
 * {@code ChunkPos} needs a bootstrapped game (its static initializer reaches the chunk statuses), which unit tests do
 * not have.
 */
public final class ChunkKeys {
    private ChunkKeys() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return chunkX & 0xFFFFFFFFL | (chunkZ & 0xFFFFFFFFL) << 32;
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }

    /** The chunk of block ({@code blockX}, {@code blockZ}). */
    public static long ofBlock(int blockX, int blockZ) {
        return pack(blockX >> 4, blockZ >> 4);
    }
}
