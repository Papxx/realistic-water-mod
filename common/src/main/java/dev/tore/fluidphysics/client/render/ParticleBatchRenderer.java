// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.tore.fluidphysics.client.ClientConfig;
import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.client.RenderMode;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.RollingSamples;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import java.util.function.IntPredicate;
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
 * Draws the client's particles, batched into at most two vertex buffers and draw calls per frame. In the
 * {@link RenderMode#SMOOTH smooth} render mode (the default) water, lava and slime become one glossy iso-surface
 * (Plan.md §8) and sand stays micro-cubes; in the {@link RenderMode#CUBES cubes} mode every particle is a micro-cube.
 * The only class that touches Minecraft's rendering API (CLAUDE.md §2.4), so changes between Minecraft versions stay
 * here; {@link ParticleMesher}, {@link FluidDensityField}, {@link MarchingCubesMesher} and {@link SmoothFluidRenderer}
 * do the version-independent work.
 *
 * <p>Cubes and surface use vanilla's {@code text_background} render type, which exists in 1.21.1 and 1.21.11 alike
 * (unlike {@code RenderType.translucent()}, which 1.21.11 dropped, Plan.md §8.4.1): quads with position, colour and
 * lightmap coordinates, alpha blending, back-face culling, depth test and write, the lightmap and the world fog. Its
 * shader has no directional shading, so the meshers bake all shading into the vertex colours, and it draws quads, so
 * the surface writes each triangle as a quad with its last vertex doubled. Buffers are drawn with
 * {@code RenderType.draw}, which skips the per-quad sort a {@code MultiBufferSource} would run on upload; the meshers
 * already sort far to near (whole cubes, surface triangles).
 *
 * <p>A smooth frame builds the density field of each fluid and meshes it, then draws the cubes first: sand, plus the
 * fluid particles whose field tiles did not fit ({@link FluidDensityField#isSkipped}), so opaque sand is in the depth
 * buffer before the translucent surface blends over it. The surface follows in a second buffer on the same vertex
 * memory, which drawing the first mesh handed back.
 *
 * <p>The loaders call {@link #render()} right after translucent terrain (Fabric {@code AFTER_TRANSLUCENT} /
 * {@code END_MAIN}, NeoForge {@code AFTER_TRANSLUCENT_BLOCKS}). At that point Minecraft's model-view matrix holds the
 * camera rotation and vertices are written relative to the camera position.
 *
 * <p>Client classes: only the loaders' client entry points may reference this class. Render thread only.
 */
public final class ParticleBatchRenderer {
    /**
     * First size of the vertex memory; it grows as needed (20 bytes per vertex: at most 12 vertices per cube, 4 per
     * surface triangle).
     */
    private static final int INITIAL_BUFFER_BYTES = 256 * 1024;
    /**
     * The animation clock is the game time modulo this many ticks (one hour), so it stays small enough for float
     * precision; the lava flicker and the ripples skip once per hour.
     */
    private static final long CLOCK_WRAP_TICKS = 72_000L;
    /** Frames covered by the render statistics: 4 seconds at 60 frames per second. */
    public static final int STATS_FRAMES = 240;
    /** The materials the smooth mode draws as a surface, fields built in this order (see {@link #isSmooth}). */
    private static final byte[] SMOOTH_MATERIALS = {
            ParticleSoA.MATERIAL_WATER, ParticleSoA.MATERIAL_LAVA, ParticleSoA.MATERIAL_SLIME};

    private static final ParticleMesher MESHER = new ParticleMesher();
    private static final BlockCache BLOCKS = new BlockCache();
    private static final BufferSink SINK = new BufferSink();
    private static final CubeFilter CUBE_FILTER = new CubeFilter();
    /** Native vertex memory, kept between frames; freed when the simulation frees its particles. */
    private static ByteBufferBuilder buffer;
    /**
     * Scratch of the smooth mode, together up to about 30 MB: created on the first smooth frame, dropped when the
     * simulation frees its particles or the mode changes to cubes.
     */
    private static FluidDensityField field;
    private static MarchingCubesMesher surface;
    private static SmoothFluidRenderer shading;
    /**
     * Per particle, smooth mode: drawn as a cube this frame because its field tiles did not fit. Sized to the
     * simulation's capacity; {@link #fallbackMarked} says whether it needs clearing.
     */
    private static boolean[] fallback;
    private static boolean fallbackMarked;
    /** Mode of the last frame drawn, null before the first; a change clears the statistics, which differ per mode. */
    private static RenderMode lastMode;

    private static int lastDrawn;
    private static int lastVertices;
    private static int lastTiles;
    private static int lastMeshedTiles;
    private static int lastTriangles;
    private static int lastFallback;
    /** Per frame with particles: CPU time of {@link #render()}, of its meshing part, and bytes it allocated. */
    private static final RollingSamples FRAME_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples MESH_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples FRAME_ALLOCATED = new RollingSamples(STATS_FRAMES);
    /** Per smooth frame with particles: CPU time of the field builds, of meshing and sorting, and of shading. */
    private static final RollingSamples FIELD_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples SURFACE_NANOS = new RollingSamples(STATS_FRAMES);
    private static final RollingSamples SHADE_NANOS = new RollingSamples(STATS_FRAMES);

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
        lastTiles = 0;
        lastMeshedTiles = 0;
        lastTriangles = 0;
        lastFallback = 0;
        if (particles == null) {
            releaseBuffer();
            dropSmoothScratch();
            clearStats();
            lastMode = null;
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (particles.activeCount == 0 || !camera.isInitialized()) {
            return;
        }
        RenderMode mode = ClientConfig.renderMode();
        if (mode != lastMode) {
            // One mode's numbers say nothing about the other's, and cubes need none of the smooth scratch.
            clearStats();
            if (mode != RenderMode.SMOOTH) {
                dropSmoothScratch();
            }
            lastMode = mode;
        }
        long start = System.nanoTime();
        long allocatedBefore = AllocationMeter.currentThreadBytes();
        // Frozen with /tick freeze, this is 1: the particles do not tick either and sit at their current positions.
        float partialTick = partialTick(minecraft);
        //? if >=1.21.11 {
        Vec3 cameraPos = camera.position();
        Vector3fc forward = camera.forwardVector();
        //?} else {
        /*Vec3 cameraPos = camera.getPosition();
        Vector3fc forward = camera.getLookVector();
        *///?}
        float time = (level.getGameTime() % CLOCK_WRAP_TICKS) + partialTick;
        SINK.vertices = 0;
        BLOCKS.begin(level);
        long meshNanos = mode == RenderMode.SMOOTH
                ? drawSmooth(simulation, particles, partialTick, cameraPos, forward, time)
                : drawCubes(particles, partialTick, cameraPos, forward, null, time);
        BLOCKS.end();
        lastVertices = SINK.vertices;
        FRAME_NANOS.add(System.nanoTime() - start);
        MESH_NANOS.add(meshNanos);
        FRAME_ALLOCATED.add(AllocationMeter.currentThreadBytes() - allocatedBefore);
    }

    /**
     * Interpolates, culls, sorts, meshes and draws the particles {@code include} accepts as cubes (null: all).
     *
     * @return CPU nanoseconds of the meshing part (everything but the upload and the draw call)
     */
    private static long drawCubes(ParticleSoA particles, float partialTick, Vec3 cameraPos, Vector3fc forward,
                                  IntPredicate include, float time) {
        long start = System.nanoTime();
        int count = MESHER.prepare(particles, partialTick, cameraPos.x, cameraPos.y, cameraPos.z,
                forward.x(), forward.y(), forward.z(), include);
        lastDrawn = count;
        if (count == 0) {
            return System.nanoTime() - start;
        }
        RenderType type = renderType();
        BufferBuilder builder = beginBuffer(type);
        MESHER.emit(particles, BLOCKS, SINK, time);
        long meshNanos = System.nanoTime() - start;
        draw(builder, type);
        return meshNanos;
    }

    /**
     * A smooth frame: builds and meshes the field of each fluid, draws the cubes (sand and the fluid particles the
     * field had no room for), then the sorted surface.
     *
     * @return CPU nanoseconds of the meshing parts (field, surface, shading and the cubes' meshing)
     */
    private static long drawSmooth(FluidSimulation simulation, ParticleSoA particles, float partialTick,
                                   Vec3 cameraPos, Vector3fc forward, float time) {
        if (field == null) {
            field = new FluidDensityField();
            surface = new MarchingCubesMesher();
            shading = new SmoothFluidRenderer();
        }
        resetFallback(particles.capacity);
        int n = particles.activeCount;
        int tiles = 0;
        int fallbackCount = 0;
        long fieldNanos = 0L;
        long surfaceNanos = 0L;
        surface.begin(cameraPos.x, cameraPos.y, cameraPos.z, forward.x(), forward.y(), forward.z());
        long mark = System.nanoTime();
        for (byte material : SMOOTH_MATERIALS) {
            int built = field.build(particles, partialTick, material, simulation.voxels());
            long builtAt = System.nanoTime();
            fieldNanos += builtAt - mark;
            tiles += built;
            if (built > 0) {
                surface.mesh(field, material, time);
            }
            if (field.overflowed()) {
                fallbackCount += markSkipped(n);
            }
            mark = System.nanoTime();
            surfaceNanos += mark - builtAt;
        }
        int triangles = surface.sort();
        surfaceNanos += System.nanoTime() - mark;
        lastTiles = tiles;
        lastMeshedTiles = surface.lastTileCount();
        lastTriangles = triangles;
        lastFallback = fallbackCount;

        CUBE_FILTER.material = particles.material;
        CUBE_FILTER.fallback = fallbackCount > 0 ? fallback : null;
        long cubeNanos = drawCubes(particles, partialTick, cameraPos, forward, CUBE_FILTER, time);
        CUBE_FILTER.material = null;
        CUBE_FILTER.fallback = null;

        long shadeNanos = 0L;
        if (triangles > 0) {
            RenderType type = renderType();
            BufferBuilder builder = beginBuffer(type);
            long shadeStart = System.nanoTime();
            shading.emit(surface, BLOCKS, SINK, cameraPos.x, cameraPos.y, cameraPos.z, time);
            shadeNanos = System.nanoTime() - shadeStart;
            draw(builder, type);
        }
        FIELD_NANOS.add(fieldNanos);
        SURFACE_NANOS.add(surfaceNanos);
        SHADE_NANOS.add(shadeNanos);
        return fieldNanos + surfaceNanos + cubeNanos + shadeNanos;
    }

    /** Marks the particles the last field build skipped (no room for their tiles) as cubes; returns how many. */
    private static int markSkipped(int n) {
        int marked = 0;
        for (int i = 0; i < n; i++) {
            if (field.isSkipped(i)) {
                fallback[i] = true;
                marked++;
            }
        }
        if (marked > 0) {
            fallbackMarked = true;
        }
        return marked;
    }

    /** Clears the last frame's fallback marks, growing the array (only when the simulation's capacity grew). */
    private static void resetFallback(int capacity) {
        if (fallback == null || fallback.length < capacity) {
            fallback = new boolean[capacity];
            fallbackMarked = false;
        } else if (fallbackMarked) {
            Arrays.fill(fallback, false);
            fallbackMarked = false;
        }
    }

    /** Whether the smooth mode draws {@code material} as part of the surface (one of {@link #SMOOTH_MATERIALS}). */
    private static boolean isSmooth(byte material) {
        return material == ParticleSoA.MATERIAL_WATER || material == ParticleSoA.MATERIAL_LAVA
                || material == ParticleSoA.MATERIAL_SLIME;
    }

    /** Starts a buffer of {@code type} in the frame's vertex memory and points the sink at it. */
    private static BufferBuilder beginBuffer(RenderType type) {
        if (buffer == null) {
            buffer = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
        }
        BufferBuilder builder = new BufferBuilder(buffer, type.mode(), type.format());
        SINK.builder = builder;
        return builder;
    }

    /** Ends the buffer and draws it. */
    private static void draw(BufferBuilder builder, RenderType type) {
        SINK.builder = null;
        MeshData mesh = builder.build();
        if (mesh != null) {
            type.draw(mesh); // uploads, draws and closes the mesh, which hands the memory back to the buffer
        }
    }

    /** Mode of the last frame drawn; null before the first frame and after the particles were freed. */
    public static RenderMode lastMode() {
        return lastMode;
    }

    /**
     * Particles drawn as cubes in the last frame (after culling): all of them in the cubes mode, sand and the fallback
     * particles in the smooth mode.
     */
    public static int lastDrawn() {
        return lastDrawn;
    }

    /** Vertices written in the last frame, cubes and surface together. */
    public static int lastVertices() {
        return lastVertices;
    }

    /** Field tiles built in the last smooth frame, all fluids together. */
    public static int lastTiles() {
        return lastTiles;
    }

    /** Field tiles meshed in the last smooth frame (tiles behind the camera are skipped). */
    public static int lastMeshedTiles() {
        return lastMeshedTiles;
    }

    /** Surface triangles drawn in the last smooth frame. */
    public static int lastTriangles() {
        return lastTriangles;
    }

    /** Fluid particles that fell back to cubes in the last smooth frame because their field tiles did not fit. */
    public static int lastFallback() {
        return lastFallback;
    }

    /** CPU time of the last {@link #STATS_FRAMES} frames with particles: meshing, upload and draw calls. */
    public static RollingSamples frameNanos() {
        return FRAME_NANOS;
    }

    /**
     * The meshing part of {@link #frameNanos()}: everything but upload and draw calls (interpolation, culling, sorting,
     * colours and vertices of the cubes; in the smooth mode also field, surface and shading).
     */
    public static RollingSamples meshNanos() {
        return MESH_NANOS;
    }

    /** Heap bytes the render thread allocated in {@link #render()}, per frame with particles. */
    public static RollingSamples frameAllocated() {
        return FRAME_ALLOCATED;
    }

    /** Smooth mode, per frame: CPU time of building the density fields of all fluids. */
    public static RollingSamples fieldNanos() {
        return FIELD_NANOS;
    }

    /** Smooth mode, per frame: CPU time of marching cubes and sorting the triangles. */
    public static RollingSamples surfaceNanos() {
        return SURFACE_NANOS;
    }

    /** Smooth mode, per frame: CPU time of shading the surface and writing its vertices. */
    public static RollingSamples shadeNanos() {
        return SHADE_NANOS;
    }

    private static void clearStats() {
        FRAME_NANOS.clear();
        MESH_NANOS.clear();
        FRAME_ALLOCATED.clear();
        FIELD_NANOS.clear();
        SURFACE_NANOS.clear();
        SHADE_NANOS.clear();
    }

    private static void releaseBuffer() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    /** Frees the smooth mode's scratch; the next smooth frame creates it again. */
    private static void dropSmoothScratch() {
        field = null;
        surface = null;
        shading = null;
        fallback = null;
        fallbackMarked = false;
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

    /** Writes the meshers' vertices into the current buffer. */
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
     * Which particles the smooth mode draws as cubes: those without a surface (sand) and the fluid particles marked as
     * fallback this frame. One long-lived object, so the frame creates no lambda.
     */
    private static final class CubeFilter implements IntPredicate {
        byte[] material;
        /** This frame's fallback marks, or null when there are none. */
        boolean[] fallback;

        @Override
        public boolean test(int i) {
            return !isSmooth(material[i]) || (fallback != null && fallback[i]);
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
