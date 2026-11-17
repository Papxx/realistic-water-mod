// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.ParticleSoA;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MarchingCubesMesherTest {
    private static final int ORIGIN_X = -37;
    private static final int ORIGIN_Y = 70;
    private static final int ORIGIN_Z = 12;
    /** Camera above and in front (+z) of the scenes, looking down at origin-relative (1, 0, 0). */
    private static final double CAM_X = ORIGIN_X + 1.3;
    private static final double CAM_Y = ORIGIN_Y + 4.2;
    private static final double CAM_Z = ORIGIN_Z + 8.1;
    private static final float[] FORWARD = normalize(-0.3f, -4.2f, -8.1f);
    private static final float TIME_TICKS = 13f;

    @Test
    void aPuddleSpanningSeveralTilesIsClosedAndSmooth() {
        Scene scene = puddle();
        FluidDensityField field = new FluidDensityField();
        int tiles = field.build(scene.particles, 0.5f, MATERIAL_WATER, scene.voxels);
        MarchingCubesMesher mesher = new MarchingCubesMesher();
        mesher.setCulling(false);

        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);
        mesher.sort();

        assertTrue(tiles >= 8, "the puddle crosses tile borders in x, y and z, got " + tiles + " tiles");
        assertEquals(tiles, mesher.lastTileCount());
        MeshReport report = checkClosedMesh(mesher);
        System.out.printf("Puddle: %d tiles, %d triangles, %d vertices, %d welded, volume %.3f%n", tiles,
                mesher.triangleCount(), mesher.vertexCount(), report.weldedVertices, report.volume);
        // Two layers of 0.25 blocks over 8 x 4 blocks; the field extends the slab a little at every side.
        assertTrue(report.volume > 8 * 4 * 0.5 * 0.7 && report.volume < 9.5 * 5.5 * 1.0,
                "puddle volume " + report.volume);
        for (int v = 0; v < mesher.vertexCount(); v++) {
            assertEquals(MATERIAL_WATER, mesher.vertexMaterial(v));
        }
    }

    @Test
    void aWaterStreamLandingInAPoolIsClosedAndRipples() {
        Scene scene = stream();
        FluidDensityField field = new FluidDensityField();
        field.build(scene.particles, 0.5f, MATERIAL_WATER, scene.voxels);
        MarchingCubesMesher mesher = new MarchingCubesMesher();
        mesher.setCulling(false);

        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);
        mesher.sort();
        MeshReport report = checkClosedMesh(mesher);
        System.out.printf("Stream: %d tiles, %d triangles, %d vertices, %d welded, volume %.3f%n",
                field.tileCount(), mesher.triangleCount(), mesher.vertexCount(), report.weldedVertices, report.volume);

        // The same field a little later: the geometry stays, the ripple rings travel and tilt the normals.
        Snapshot before = Snapshot.of(mesher);
        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS + 7f);
        Snapshot after = Snapshot.of(mesher);
        assertEquals(before.positions.length, after.positions.length);
        for (int i = 0; i < before.positions.length; i++) {
            assertEquals(before.positions[i], after.positions[i], "ripples must not move vertices");
        }
        int rippled = before.normalsChangedFrom(after);
        assertTrue(rippled > 20, "only " + rippled + " normals ripple under the stream");
    }

    @Test
    void aSingleDropletIsASmallClosedSurface() {
        // A lone particle centred on a node: the smallest surface there is (about three cells across), perfectly
        // symmetric, so gradients cancel exactly at its centre.
        ParticleSoA particles = new ParticleSoA(4);
        particles.add(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, MATERIAL_WATER);
        FluidDensityField field = new FluidDensityField();
        field.build(particles, 0f, MATERIAL_WATER, null);
        MarchingCubesMesher mesher = new MarchingCubesMesher();
        mesher.setCulling(false);
        mesher.begin(0.5, 3, 4, 0f, -0.6f, -0.8f);
        mesher.mesh(field, MATERIAL_WATER, 0f);

        MeshReport report = checkClosedMesh(mesher);
        assertFalse(field.overflowed());
        // The iso-surface of one kernel is a sphere of radius R * sqrt(1 - cbrt(ISO)), about 0.174 blocks; at under
        // 1.5 cells radius the marched polyhedron is coarse, so only its order of magnitude is checked.
        double radius = FluidDensityField.KERNEL_RADIUS
                * Math.sqrt(1.0 - Math.cbrt(MarchingCubesMesher.ISO_LEVEL));
        double sphere = 4.0 / 3.0 * Math.PI * radius * radius * radius;
        assertTrue(report.volume > 0.25 * sphere && report.volume < 1.3 * sphere,
                "droplet volume " + report.volume + ", sphere " + sphere);
    }

    @Test
    void calmWaterDoesNotRipple() {
        Scene scene = puddle();
        FluidDensityField field = new FluidDensityField();
        field.build(scene.particles, 0.5f, MATERIAL_WATER, scene.voxels);
        MarchingCubesMesher mesher = new MarchingCubesMesher();

        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);
        Snapshot before = Snapshot.of(mesher);
        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS + 7f);

        assertEquals(0, before.normalsChangedFrom(Snapshot.of(mesher)));
    }

    @Test
    void cullingKeepsOnlyFrontFacesInFrontOfTheCameraSortedFarToNear() {
        Scene scene = puddle();
        FluidDensityField field = new FluidDensityField();
        field.build(scene.particles, 0.5f, MATERIAL_WATER, scene.voxels);
        MarchingCubesMesher mesher = new MarchingCubesMesher();
        mesher.setCulling(false);
        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);
        int all = mesher.triangleCount();

        mesher.setCulling(true);
        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);
        int visible = mesher.sort();

        assertTrue(visible > 0 && visible < all, visible + " of " + all + " triangles survive culling");
        float previousDistance = Float.MAX_VALUE;
        for (int t = 0; t < visible; t++) {
            int a = mesher.triangleVertex(t, 0);
            int b = mesher.triangleVertex(t, 1);
            int c = mesher.triangleVertex(t, 2);
            assertTrue(facesCamera(mesher, a, b, c), "triangle " + t + " faces away from the camera");
            float cx = (mesher.vertexX(a) + mesher.vertexX(b) + mesher.vertexX(c)) / 3f;
            float cy = (mesher.vertexY(a) + mesher.vertexY(b) + mesher.vertexY(c)) / 3f;
            float cz = (mesher.vertexZ(a) + mesher.vertexZ(b) + mesher.vertexZ(c)) / 3f;
            assertTrue(cx * FORWARD[0] + cy * FORWARD[1] + cz * FORWARD[2] >= -MarchingCubesMesher.CULL_MARGIN - 1e-4f,
                    "triangle " + t + " lies behind the camera");
            float distance = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            // Sort keys resolve 1/32 block.
            assertTrue(distance <= previousDistance + 1f / 32f + 1e-4f, "triangle " + t + " is out of order");
            previousDistance = Math.min(previousDistance, distance);
        }
    }

    @Test
    void tilesBehindTheCameraAreSkipped() {
        Scene scene = puddle();
        FluidDensityField field = new FluidDensityField();
        int tiles = field.build(scene.particles, 0.5f, MATERIAL_WATER, scene.voxels);
        MarchingCubesMesher mesher = new MarchingCubesMesher();

        // Looking away from the puddle (+z, the puddle lies at -z).
        mesher.begin(CAM_X, CAM_Y, CAM_Z, 0f, 0f, 1f);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS);

        assertTrue(mesher.lastTileCount() < tiles);
        assertEquals(0, mesher.triangleCount());
    }

    @Test
    void meshingAFrameAllocatesNothing() {
        Scene scene = stream();
        FluidDensityField field = new FluidDensityField();
        MarchingCubesMesher mesher = new MarchingCubesMesher();
        int frames = 20;
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            for (int i = 0; i < frames; i++) {
                meshFrame(scene, field, mesher, i, frames);
            }
        }

        long before = AllocationMeter.currentThreadBytes();
        long start = System.nanoTime();
        int triangles = 0;
        for (int i = 0; i < frames; i++) {
            triangles = meshFrame(scene, field, mesher, i, frames);
        }
        double msPerFrame = (System.nanoTime() - start) / (double) frames / 1.0e6;
        long allocated = AllocationMeter.currentThreadBytes() - before;
        System.out.printf("MarchingCubesMesher @ %d particles: %.2f ms per frame (build + mesh + sort), %d tiles, "
                        + "%d triangles in the last frame, %d bytes allocated in %d frames%n",
                scene.particles.activeCount, msPerFrame, field.tileCount(), triangles, allocated, frames);

        assertTrue(triangles > 0);
        assertEquals(0L, allocated, "building, meshing and sorting must not allocate");
    }

    private static int meshFrame(Scene scene, FluidDensityField field, MarchingCubesMesher mesher, int i, int frames) {
        field.build(scene.particles, i / (float) frames, MATERIAL_WATER, scene.voxels);
        mesher.begin(CAM_X, CAM_Y, CAM_Z, FORWARD[0], FORWARD[1], FORWARD[2]);
        mesher.mesh(field, MATERIAL_WATER, TIME_TICKS + i);
        return mesher.sort();
    }

    // ---- scenes ----

    /** Particles plus the stone floor under them (its top surface at origin-relative y = 0). */
    private record Scene(ParticleSoA particles, ChunkVoxelCache voxels) {
    }

    /** Two resting layers of water at 0.25 spacing on the floor, x from -3 to 5 and z from -2 to 2 blocks. */
    private static Scene puddle() {
        ParticleSoA particles = new ParticleSoA(2048);
        particles.setOrigin(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        Random random = new Random(11);
        for (int layer = 0; layer < 2; layer++) {
            for (int iz = 0; iz < 16; iz++) {
                for (int ix = 0; ix < 32; ix++) {
                    particles.add(-3f + (ix + 0.5f) * 0.25f + jitter(random), (layer + 0.5f) * 0.25f + jitter(random),
                            -2f + (iz + 0.5f) * 0.25f + jitter(random), 0f, 0f, 0f, MATERIAL_WATER);
                }
            }
        }
        return new Scene(particles, floor());
    }

    /**
     * A small resting pool (x and z from -1 to 3 blocks, two layers) with a column of water falling into its middle
     * at about 8 blocks per second; the water around the landing point splashes.
     */
    private static Scene stream() {
        ParticleSoA particles = new ParticleSoA(1024);
        particles.setOrigin(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        Random random = new Random(5);
        for (int layer = 0; layer < 2; layer++) {
            for (int iz = 0; iz < 16; iz++) {
                for (int ix = 0; ix < 16; ix++) {
                    float x = -1f + (ix + 0.5f) * 0.25f + jitter(random);
                    float z = -1f + (iz + 0.5f) * 0.25f + jitter(random);
                    float y = (layer + 0.5f) * 0.25f + jitter(random);
                    boolean splash = (x - 1f) * (x - 1f) + (z - 1f) * (z - 1f) < 0.36f;
                    float stir = splash ? 2.5f : 0.2f;
                    addMoving(particles, x, y, z, (random.nextFloat() - 0.5f) * stir,
                            (random.nextFloat() - 0.2f) * stir, (random.nextFloat() - 0.5f) * stir);
                }
            }
        }
        for (int row = 0; row < 14; row++) {
            for (int k = 0; k < 4; k++) {
                float x = 1f + ((k & 1) - 0.5f) * 0.25f + jitter(random);
                float z = 1f + ((k >> 1) - 0.5f) * 0.25f + jitter(random);
                float y = 0.7f + row * 0.25f + jitter(random);
                addMoving(particles, x, y, z, (random.nextFloat() - 0.5f) * 0.6f, -8f + (random.nextFloat() - 0.5f),
                        (random.nextFloat() - 0.5f) * 0.6f);
            }
        }
        return new Scene(particles, floor());
    }

    /** Adds a particle that moved with its velocity during the last tick, so render interpolation matters. */
    private static void addMoving(ParticleSoA particles, float x, float y, float z, float vx, float vy, float vz) {
        int i = particles.add(x, y, z, vx, vy, vz, MATERIAL_WATER);
        float tick = 0.05f;
        particles.prevX[i] = x - vx * tick;
        particles.prevY[i] = y - vy * tick;
        particles.prevZ[i] = z - vz * tick;
    }

    private static ChunkVoxelCache floor() {
        ChunkVoxelCache voxels = new ChunkVoxelCache();
        for (int x = -6; x <= 8; x++) {
            for (int z = -6; z <= 6; z++) {
                voxels.putMask(ORIGIN_X + x, ORIGIN_Y - 1, ORIGIN_Z + z, ChunkVoxelCache.FULL);
            }
        }
        return voxels;
    }

    private static float jitter(Random random) {
        return (random.nextFloat() - 0.5f) * 0.04f;
    }

    // ---- mesh checks ----

    private record MeshReport(int weldedVertices, double volume) {
    }

    private record Position(int x, int y, int z) {
        static Position of(MarchingCubesMesher mesh, int v) {
            return new Position(Float.floatToIntBits(mesh.vertexX(v)), Float.floatToIntBits(mesh.vertexY(v)),
                    Float.floatToIntBits(mesh.vertexZ(v)));
        }
    }

    /**
     * Checks a mesh made without culling: finite positions, unit normals, vertices welded by exact position form a
     * closed and consistently oriented surface (every directed edge once, its reverse once), outward orientation
     * (positive volume) and face normals that agree with the vertex normals.
     */
    private static MeshReport checkClosedMesh(MarchingCubesMesher mesh) {
        int triangles = mesh.triangleCount();
        int vertices = mesh.vertexCount();
        assertTrue(triangles > 0, "no triangles");
        Map<Position, Integer> weld = new HashMap<>();
        int[] id = new int[vertices];
        for (int v = 0; v < vertices; v++) {
            float x = mesh.vertexX(v);
            float y = mesh.vertexY(v);
            float z = mesh.vertexZ(v);
            assertTrue(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "vertex " + v + " not finite");
            float nx = mesh.normalX(v);
            float ny = mesh.normalY(v);
            float nz = mesh.normalZ(v);
            assertTrue(Float.isFinite(nx) && Float.isFinite(ny) && Float.isFinite(nz), "normal " + v + " not finite");
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertEquals(1.0, length, 1e-3, "normal " + v + " is not unit length");
            float thickness = mesh.thickness(v);
            assertTrue(thickness >= 0f && thickness <= 1f, "thickness " + thickness);
            Integer existing = weld.putIfAbsent(Position.of(mesh, v), weld.size());
            id[v] = existing == null ? weld.size() - 1 : existing;
        }

        Map<Long, Integer> edges = new HashMap<>();
        double volume = 0;
        int agreeing = 0;
        for (int t = 0; t < triangles; t++) {
            int a = mesh.triangleVertex(t, 0);
            int b = mesh.triangleVertex(t, 1);
            int c = mesh.triangleVertex(t, 2);
            int wa = id[a];
            int wb = id[b];
            int wc = id[c];
            assertTrue(wa != wb && wb != wc && wc != wa, "triangle " + t + " is degenerate");
            addEdge(edges, wa, wb);
            addEdge(edges, wb, wc);
            addEdge(edges, wc, wa);

            double ax = mesh.vertexX(a);
            double ay = mesh.vertexY(a);
            double az = mesh.vertexZ(a);
            double e1x = mesh.vertexX(b) - ax;
            double e1y = mesh.vertexY(b) - ay;
            double e1z = mesh.vertexZ(b) - az;
            double e2x = mesh.vertexX(c) - ax;
            double e2y = mesh.vertexY(c) - ay;
            double e2z = mesh.vertexZ(c) - az;
            double fx = e1y * e2z - e1z * e2y;
            double fy = e1z * e2x - e1x * e2z;
            double fz = e1x * e2y - e1y * e2x;
            // Signed volume of the tetrahedron (o, a, b, c) = a . (b x c) / 6 = a . (e1 x e2) / 6.
            volume += (ax * fx + ay * fy + az * fz) / 6.0;
            double nx = mesh.normalX(a) + mesh.normalX(b) + mesh.normalX(c);
            double ny = mesh.normalY(a) + mesh.normalY(b) + mesh.normalY(c);
            double nz = mesh.normalZ(a) + mesh.normalZ(b) + mesh.normalZ(c);
            if (fx * nx + fy * ny + fz * nz > 0) {
                agreeing++;
            }
        }
        for (Map.Entry<Long, Integer> edge : edges.entrySet()) {
            long key = edge.getKey();
            int from = (int) (key >>> 32);
            int to = (int) key;
            if (!edges.containsKey(edgeKey(to, from))) {
                fail("edge " + from + " -> " + to + " has no reverse: the mesh has a hole or a flipped triangle");
            }
        }
        assertTrue(volume > 0, "signed volume " + volume + ": triangles are not wound outward");
        assertTrue(agreeing >= triangles * 0.95, agreeing + " of " + triangles
                + " face normals agree with the vertex normals");
        return new MeshReport(weld.size(), volume);
    }

    private static void addEdge(Map<Long, Integer> edges, int from, int to) {
        if (edges.put(edgeKey(from, to), 1) != null) {
            fail("directed edge " + from + " -> " + to + " is used twice: non-manifold or inconsistent winding");
        }
    }

    private static long edgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    /**
     * The contract's front-face test, with exactly the mesher's float arithmetic: the camera sits at the origin, so
     * the triangle faces it iff {@code ((v1 - v0) x (v2 - v0)) . v0 < 0}.
     */
    private static boolean facesCamera(MarchingCubesMesher mesh, int a, int b, int c) {
        float x0 = mesh.vertexX(a);
        float y0 = mesh.vertexY(a);
        float z0 = mesh.vertexZ(a);
        float e1x = mesh.vertexX(b) - x0;
        float e1y = mesh.vertexY(b) - y0;
        float e1z = mesh.vertexZ(b) - z0;
        float e2x = mesh.vertexX(c) - x0;
        float e2y = mesh.vertexY(c) - y0;
        float e2z = mesh.vertexZ(c) - z0;
        float crossX = e1y * e2z - e1z * e2y;
        float crossY = e1z * e2x - e1x * e2z;
        float crossZ = e1x * e2y - e1y * e2x;
        return crossX * x0 + crossY * y0 + crossZ * z0 < 0f;
    }

    /** Positions and normals of every vertex, in creation order. */
    private record Snapshot(float[] positions, float[] normals) {
        static Snapshot of(MarchingCubesMesher mesh) {
            int n = mesh.vertexCount();
            float[] positions = new float[n * 3];
            float[] normals = new float[n * 3];
            for (int v = 0; v < n; v++) {
                positions[v * 3] = mesh.vertexX(v);
                positions[v * 3 + 1] = mesh.vertexY(v);
                positions[v * 3 + 2] = mesh.vertexZ(v);
                normals[v * 3] = mesh.normalX(v);
                normals[v * 3 + 1] = mesh.normalY(v);
                normals[v * 3 + 2] = mesh.normalZ(v);
            }
            return new Snapshot(positions, normals);
        }

        /** Number of vertices whose normal differs by more than 1e-3 from the other snapshot's. */
        int normalsChangedFrom(Snapshot other) {
            assertEquals(normals.length, other.normals.length);
            int changed = 0;
            for (int v = 0; v < normals.length / 3; v++) {
                float dx = normals[v * 3] - other.normals[v * 3];
                float dy = normals[v * 3 + 1] - other.normals[v * 3 + 1];
                float dz = normals[v * 3 + 2] - other.normals[v * 3 + 2];
                if (dx * dx + dy * dy + dz * dz > 1e-6f) {
                    changed++;
                }
            }
            return changed;
        }
    }

    private static float[] normalize(float x, float y, float z) {
        float inv = (float) (1.0 / Math.sqrt(x * x + y * y + z * z));
        return new float[] {x * inv, y * inv, z * inv};
    }
}
