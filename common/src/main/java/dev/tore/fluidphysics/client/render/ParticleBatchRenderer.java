// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.RollingSamples;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;
*///?}

/**
 * Draws the client's particles as batched micro-cubes: one vertex buffer and one draw call per frame for all of them.
 * The only class that touches Minecraft's rendering API (CLAUDE.md §2.4), so changes between Minecraft versions stay
 * here; {@link ParticleMesher} does the version-independent work.
 *
 * <p>The cubes use vanilla's {@code text_background} render type, which exists in 1.21.1 and 1.21.11 alike: position,
 * colour and lightmap coordinates, alpha blending, back-face culling, depth test and write, the lightmap and the world
 * fog. Its shader has no directional shading, so the mesher bakes face shading into the colours. The buffer is drawn
 * with {@code RenderType.draw}, which skips the per-quad sort a {@code MultiBufferSource} would run on upload; the
 * mesher already sorts whole particles far to near.
 *
 * <p>The loaders call {@link #render()} right after translucent terrain (Fabric {@code AFTER_TRANSLUCENT} /
 * {@code END_MAIN}, NeoForge {@code AFTER_TRANSLUCENT_BLOCKS}). At that point Minecraft's model-view matrix holds the
 * camera rotation and vertices are written relative to the camera position.
 *
 * <p>Client classes: only the loaders' client entry points may reference this class. Render thread only.
 */
public final class ParticleBatchRenderer {
    /** First size of the vertex memory; it grows as needed (20 bytes per vertex, at most 12 vertices per particle). */
    private static final int INITIAL_BUFFER_BYTES = 256 * 1024;
    /**
     * The animation clock is the game time modulo this many ticks (one hour), so it stays small enough for float
     * precision; the lava flicker skips once per hour.
     */
    private static final long CLOCK_WRAP_TICKS = 72_000L;
    /** Frames covered by the render statistics: 4 seconds at 60 frames per second. */
    public static final int STATS_FRAMES = 240;

    private static final ParticleMesher MESHER = new ParticleMesher();
    private static final BlockCache BLOCKS = new BlockCache();
    private static final BufferSink SINK = new BufferSink();
    /** Native vertex memory, kept between frames; freed when the simulation frees its particles. */
    private static ByteBufferBuilder buffer;
    private static int lastDrawn;
    private static int lastVertices;
    /** Per frame with particles: CPU time of {@link #render()}, of its meshing part, and bytes it allocated. */
    private static final RollingSamples FRAME_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples MESH_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples FRAME_ALLOCATED = new RollingSamples(STATS_FRAMES);

    private ParticleBatchRenderer() {
    }

    /** Draws the particles of the level being rendered; call once per frame after translucent terrain. */
    public static void render() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        FluidSimulation simulation = ClientSimulation.currentFor(level);
        ParticleSoA particles = simulation == null ? null : simulation.particles();
        lastDrawn = 0;
        lastVertices = 0;
        if (particles == null) {
            releaseBuffer();
            FRAME_NANOS.clear();
            MESH_NANOS.clear();
            FRAME_ALLOCATED.clear();
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (particles.activeCount == 0 || !camera.isInitialized()) {
            return;
        }
        long start = System.nanoTime();
        long allocatedBefore = AllocationMeter.currentThreadBytes();
        long meshNanos = 0L;
        // Frozen with /tick freeze, this is 1: the particles do not tick either and sit at their current positions.
        float partialTick = partialTick(minecraft);
        //? if >=1.21.11 {
        Vec3 cameraPos = camera.position();
        Vector3fc forward = camera.forwardVector();
        //?} else {
        /*Vec3 cameraPos = camera.getPosition();
        Vector3fc forward = camera.getLookVector();
        *///?}
        int count = MESHER.prepare(particles, partialTick, cameraPos.x, cameraPos.y, cameraPos.z,
                forward.x(), forward.y(), forward.z());
        if (count > 0) {
            RenderType type = renderType();
            if (buffer == null) {
                buffer = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
            }
            BufferBuilder builder = new BufferBuilder(buffer, type.mode(), type.format());
            SINK.builder = builder;
            SINK.vertices = 0;
            BLOCKS.begin(level);
            MESHER.emit(particles, BLOCKS, SINK, (level.getGameTime() % CLOCK_WRAP_TICKS) + partialTick);
            BLOCKS.end();
            SINK.builder = null;
            lastVertices = SINK.vertices;
            meshNanos = System.nanoTime() - start;
            MeshData mesh = builder.build();
            if (mesh != null) {
                type.draw(mesh); // uploads, draws and closes the mesh, which hands the memory back to the buffer
            }
        }
        lastDrawn = count;
        long end = System.nanoTime();
        FRAME_NANOS.add(end - start);
        MESH_NANOS.add(count > 0 ? meshNanos : end - start);
        FRAME_ALLOCATED.add(AllocationMeter.currentThreadBytes() - allocatedBefore);
    }

    /** Particles drawn in the last frame (after culling). */
    public static int lastDrawn() {
        return lastDrawn;
    }

    /** Vertices written in the last frame. */
    public static int lastVertices() {
        return lastVertices;
    }

    /** CPU time of the last {@link #STATS_FRAMES} frames with particles: meshing, upload and draw call. */
    public static RollingSamples frameNanos() {
        return FRAME_NANOS;
    }

    /** The meshing part of {@link #frameNanos()}: interpolation, culling, sorting, colours and vertices. */
    public static RollingSamples meshNanos() {
        return MESH_NANOS;
    }

    /** Heap bytes the render thread allocated in {@link #render()}, per frame with particles. */
    public static RollingSamples frameAllocated() {
        return FRAME_ALLOCATED;
    }

    private static void releaseBuffer() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    private static float partialTick(Minecraft minecraft) {
        //? if >=1.21.11 {
        return minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        //?} else {
        /*return minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        *///?}
    }

    private static RenderType renderType() {
        //? if >=1.21.11 {
        return RenderTypes.textBackground();
        //?} else {
        /*return RenderType.textBackground();
        *///?}
    }

    /** Writes the mesher's vertices into the frame's buffer. */
    private static final class BufferSink implements ParticleMesher.VertexSink {
        BufferBuilder builder;
        int vertices;

        @Override
        public void vertex(float x, float y, float z, int argb, int packedLight) {
            builder.addVertex(x, y, z).setColor(argb).setLight(packedLight);
            vertices++;
        }
    }

    /**
     * Light and biome water colour per block, looked up at most once per block and frame: 64 particles share a block,
     * so a frame with 32,768 particles asks the level a few hundred times instead of tens of thousands. Direct-mapped;
     * a frame stamp invalidates all entries without clearing the arrays.
     */
    private static final class BlockCache implements ParticleMesher.BlockEnvironment {
        private static final int SIZE = 4096;
        private static final int NO_COLOR = -1;

        private final long[] keys = new long[SIZE];
        private final int[] stamps = new int[SIZE];
        private final int[] lights = new int[SIZE];
        private final int[] waterColors = new int[SIZE];
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private ClientLevel level;
        private int frame;

        void begin(ClientLevel level) {
            this.level = level;
            if (++frame == 0) {
                // The stamp wrapped around: entries from 2^32 frames ago would look current.
                Arrays.fill(stamps, 0);
                frame = 1;
            }
        }

        void end() {
            level = null;
        }

        @Override
        public int packedLight(int blockX, int blockY, int blockZ) {
            return lights[slot(blockX, blockY, blockZ)];
        }

        @Override
        public int waterColor(int blockX, int blockY, int blockZ) {
            int slot = slot(blockX, blockY, blockZ);
            int color = waterColors[slot];
            if (color == NO_COLOR) {
                color = BiomeColors.getAverageWaterColor(level, pos.set(blockX, blockY, blockZ)) & 0xFFFFFF;
                waterColors[slot] = color;
            }
            return color;
        }

        /** The cache slot of a block, filled with its light for this frame. */
        private int slot(int blockX, int blockY, int blockZ) {
            long key = BlockPos.asLong(blockX, blockY, blockZ);
            int slot = (int) HashCommon.mix(key) & (SIZE - 1);
            if (stamps[slot] != frame || keys[slot] != key) {
                pos.set(blockX, blockY, blockZ);
                keys[slot] = key;
                stamps[slot] = frame;
                lights[slot] = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos),
                        level.getBrightness(LightLayer.SKY, pos));
                waterColors[slot] = NO_COLOR;
            }
            return slot;
        }
    }
}
