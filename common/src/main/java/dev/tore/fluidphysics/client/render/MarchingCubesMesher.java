// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.client.render.FluidDensityField.CELL_SIZE;
import static dev.tore.fluidphysics.client.render.FluidDensityField.INV_CELL_SIZE;
import static dev.tore.fluidphysics.client.render.FluidDensityField.NODE_STRIDE_Y;
import static dev.tore.fluidphysics.client.render.FluidDensityField.NODE_STRIDE_Z;
import static dev.tore.fluidphysics.client.render.FluidDensityField.TILE_CELLS;

import dev.tore.fluidphysics.physics.ParticleSoA;
import java.util.Arrays;

/**
 * Extracts the iso-surface of a {@link FluidDensityField} with marching cubes (Plan.md §8.2): camera-relative
 * triangles with smooth outward normals, a thickness per vertex for Beer-Lambert shading, ripple rings on water, back
 * face and behind-camera culling, and a far-to-near sort for blending. Plain Java without any rendering API, so it can
 * be unit tested; {@code SmoothFluidRenderer} shades and emits the result.
 *
 * <p>A frame is {@link #begin}, one {@link #mesh} per material (each right after building the field for it), then
 * {@link #sort}. Output vertices are {@code node * CELL_SIZE - (float) (cam - origin)}, with the origin the field was
 * built with.
 *
 * <p><b>Watertight across tiles.</b> Each tile is meshed on its own and its vertices are only shared inside the tile
 * (an edge cache stamped per tile, so nothing is cleared between tiles). A vertex on a tile border is therefore made
 * once in each tile, but always from its lower global node with the same arithmetic, from node values the field makes
 * bit-identical in every tile: both copies get exactly the same position and normal, so the surface has no cracks and
 * welding by position gives a closed, consistently oriented mesh. The edge parameter is kept a hair away from the
 * nodes ({@link #MIN_EDGE_T}), so two different edges never produce the same position and no triangle collapses.
 *
 * <p><b>Ripples.</b> Water vertices under an impact (a stream landing, see {@link FluidDensityField}) get their normal
 * tilted along the horizontal direction away from the nearest impact source by a sine travelling outward: its phase
 * follows the distance {@code r / R} to that source, so the crests are concentric rings, and its amplitude follows the
 * impact. (For a single source of amount 1, {@code r / R} equals {@code sqrt(1 - cbrt(I))}, the inverted kernel of the
 * original design; the distance field keeps the rings intact under strong streams, where {@code I} saturates.) Only
 * shading changes; the geometry stays the same.
 *
 * <p>Allocation-free once the output arrays have grown to the largest frame seen. Not thread-safe: render thread only.
 */
public final class MarchingCubesMesher {
    /** A grid node is inside the fluid when its value is greater than this. */
    public static final float ISO_LEVEL = 0.35f;

    /**
     * Edge vertices stay at least this fraction of an edge away from its nodes. Prevents vertices of different edges
     * from coinciding (zero-area triangles) when a node value equals the iso level; moves a vertex by at most 1/8000
     * of a block.
     */
    static final float MIN_EDGE_T = 1e-3f;
    /**
     * Triangles whose centroid lies this far behind the camera plane are dropped, and tiles entirely this far behind
     * it are skipped. Covers the near plane and triangles that straddle the plane.
     */
    static final float CULL_MARGIN = 0.25f;
    /** Thickness is sampled this far (blocks) inside the surface, where the particle count is representative. */
    static final float THICKNESS_PROBE_DEPTH = 0.375f;
    private static final float THICKNESS_PROBE_CELLS = THICKNESS_PROBE_DEPTH * INV_CELL_SIZE;
    /** Ripple rings leaving the impact point per second. */
    static final float RIPPLE_SPEED = 1.5f;
    /** Rings between the impact centre and the edge of the impact field. */
    static final float RIPPLE_RINGS = 4f;
    /** Largest normal tilt of a ripple, on a fully stirred flat surface. */
    static final float RIPPLE_AMPLITUDE = 0.35f;
    /** Impact below this leaves the surface calm. */
    static final float RIPPLE_MIN_IMPACT = 0.02f;
    /** Only surfaces facing at least this much upwards (normal y) ripple. */
    static final float RIPPLE_MIN_UP = 0.2f;
    /** Half the distance (blocks) of the central difference that finds the direction away from the impact. */
    static final float RIPPLE_GRADIENT_STEP = 0.25f;
    private static final float RIPPLE_GRADIENT_CELLS = RIPPLE_GRADIENT_STEP * INV_CELL_SIZE;
    private static final float MIN_RIPPLE_GRADIENT = 1e-6f;
    /** Squared density gradients below this have no usable direction; the normal falls back to straight up. */
    private static final float MIN_GRADIENT_SQ = 1e-20f;
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double TWO_PI = 2.0 * Math.PI;
    /** Sort keys resolve distance in steps of 1/32 block, up to 2048 blocks (like {@link ParticleMesher}). */
    private static final float SORT_STEPS_PER_BLOCK = 32f;
    private static final int SORT_KEY_MAX = 0xFFFF;

    /** Nodes per axis a tile's cells use (local nodes 0..16). */
    private static final int SIDE = TILE_CELLS + 1;
    /** Row mask with all {@link #SIDE} nodes inside. */
    private static final int FULL_ROW = (1 << SIDE) - 1;
    /** Half the edge length of a tile, in blocks. */
    private static final float TILE_HALF_EXTENT = TILE_CELLS * CELL_SIZE * 0.5f;
    /** Edge cache entries: one per lower node (0..16 per axis) and axis. */
    private static final int EDGE_CACHE_SIZE = SIDE * SIDE * SIDE * 3;
    private static final int[] AXIS_STRIDE = {1, NODE_STRIDE_Y, NODE_STRIDE_Z};
    private static final int INITIAL_VERTICES = 4096;
    private static final int INITIAL_TRIANGLES = 4096;

    private static final int[][] TRI_TABLE = MarchingCubesTables.TRI_TABLE;
    // Per edge: offset of its lower node from the cell's lower corner, its axis, and derived index offsets.
    private static final int[] EDGE_DX = new int[12];
    private static final int[] EDGE_DY = new int[12];
    private static final int[] EDGE_DZ = new int[12];
    private static final int[] EDGE_AXIS = new int[12];
    /** Edge cache key of the edge relative to the key of the cell's lower corner. */
    private static final int[] EDGE_KEY = new int[12];
    /** Field index of the edge's lower node relative to the cell's lower corner node. */
    private static final int[] EDGE_NODE = new int[12];

    static {
        for (int e = 0; e < 12; e++) {
            int c = MarchingCubesTables.EDGE_START[e];
            int dx = c & 1;
            int dy = (c >> 1) & 1;
            int dz = (c >> 2) & 1;
            int axis = MarchingCubesTables.EDGE_AXIS[e];
            EDGE_DX[e] = dx;
            EDGE_DY[e] = dy;
            EDGE_DZ[e] = dz;
            EDGE_AXIS[e] = axis;
            EDGE_KEY[e] = ((dz * SIDE + dy) * SIDE + dx) * 3 + axis;
            EDGE_NODE[e] = dx + dy * NODE_STRIDE_Y + dz * NODE_STRIDE_Z;
        }
    }

    // Output: vertices (camera-relative position, unit normal, thickness, material) and triangles (3 indices each).
    private float[] vx = new float[0];
    private float[] vy = new float[0];
    private float[] vz = new float[0];
    private float[] nx = new float[0];
    private float[] ny = new float[0];
    private float[] nz = new float[0];
    private float[] thick = new float[0];
    private byte[] vmat = new byte[0];
    private int vertexCount;
    private int[] triangles = new int[0];
    private int triangleCount;
    private int tileCountSinceBegin;

    // Sort: triangle order far to near and its keys; the tmp arrays are the radix sort's second buffer.
    private int[] order = new int[0];
    private int[] orderTmp = new int[0];
    private int[] keys = new int[0];
    private int[] keysTmp = new int[0];
    private final int[] radixCount = new int[256];
    private boolean sorted;

    private double camX;
    private double camY;
    private double camZ;
    private float fwdX;
    private float fwdY;
    private float fwdZ;
    private boolean culling = true;

    // Edge cache of the tile being meshed: vertex index per (lower node, axis), valid where the stamp matches.
    private final int[] edgeStamp = new int[EDGE_CACHE_SIZE];
    private final int[] edgeVertex = new int[EDGE_CACHE_SIZE];
    private int tileSerial;
    /** Inside bits of the nodes 0..16 of each (y, z) row of the tile being meshed, bit x = node x inside. */
    private final int[] rowMask = new int[SIDE * SIDE];

    // State of the current mesh() call and tile.
    private FluidDensityField field;
    private float[] values;
    private byte material;
    private boolean ripples;
    private double rippleCycles;
    private float offX;
    private float offY;
    private float offZ;
    private int slot;
    /** Field index of the tile's local node (0, 0, 0). */
    private int nodeOrigin;
    /** Global node of the tile's local node (0, 0, 0). */
    private int tileNodeX;
    private int tileNodeY;
    private int tileNodeZ;

    // Corners of the triangle being assembled: existing vertex (or -1) and, for new ones, the edge data.
    private final int[] cornerVertex = new int[3];
    private final int[] cornerKey = new int[3];
    private final int[] cornerNode = new int[3];
    private final int[] cornerAxis = new int[3];
    private final float[] cornerT = new float[3];
    /** Corner position in global fine-node units (origin-relative), for sampling the coarse fields. */
    private final float[] cornerGX = new float[3];
    private final float[] cornerGY = new float[3];
    private final float[] cornerGZ = new float[3];
    /** Corner position relative to the camera. */
    private final float[] cornerX = new float[3];
    private final float[] cornerY = new float[3];
    private final float[] cornerZ = new float[3];

    /** Starts a frame: clears the output and sets the camera (world coordinates) and its unit view direction. */
    public void begin(double camX, double camY, double camZ, float fwdX, float fwdY, float fwdZ) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.fwdX = fwdX;
        this.fwdY = fwdY;
        this.fwdZ = fwdZ;
        vertexCount = 0;
        triangleCount = 0;
        tileCountSinceBegin = 0;
        sorted = false;
    }

    /** Turns culling of back faces and of geometry behind the camera on (the default) or off (tests: closed mesh). */
    void setCulling(boolean culling) {
        this.culling = culling;
    }

    /**
     * Appends the iso-surface of the field that was just built for {@code material}. Drops triangles that face away
     * from the camera and tiles/triangles behind the camera plane. Water gets the ripple normal perturbation.
     * {@code timeTicks} is an animation clock in ticks (only its changes matter).
     */
    public void mesh(FluidDensityField field, byte material, float timeTicks) {
        int tiles = field.tileCount();
        if (tiles == 0) {
            return;
        }
        sorted = false;
        this.field = field;
        this.values = field.values();
        this.material = material;
        ripples = material == ParticleSoA.MATERIAL_WATER;
        double cycles = RIPPLE_SPEED * (timeTicks / TICKS_PER_SECOND);
        rippleCycles = cycles - Math.floor(cycles);
        // The camera relative to the field's origin; positions are floats relative to it, like the particles.
        offX = (float) (camX - field.originX());
        offY = (float) (camY - field.originY());
        offZ = (float) (camZ - field.originZ());
        float reach = TILE_HALF_EXTENT * (Math.abs(fwdX) + Math.abs(fwdY) + Math.abs(fwdZ));
        for (int s = 0; s < tiles; s++) {
            int gx = field.tileX(s) * TILE_CELLS;
            int gy = field.tileY(s) * TILE_CELLS;
            int gz = field.tileZ(s) * TILE_CELLS;
            if (culling) {
                // Farthest reach of the tile's box along the view direction.
                float cx = (gx * CELL_SIZE + TILE_HALF_EXTENT) - offX;
                float cy = (gy * CELL_SIZE + TILE_HALF_EXTENT) - offY;
                float cz = (gz * CELL_SIZE + TILE_HALF_EXTENT) - offZ;
                if (cx * fwdX + cy * fwdY + cz * fwdZ + reach < -CULL_MARGIN) {
                    continue;
                }
            }
            tileCountSinceBegin++;
            meshTile(s, gx, gy, gz);
        }
        this.field = null;
        this.values = null;
    }

    /** Marches the cells of one tile. Tiles entirely inside or outside the fluid are skipped after one pass. */
    private void meshTile(int s, int gx, int gy, int gz) {
        float[] v = values;
        int origin = FluidDensityField.tileBase(s) + FluidDensityField.nodeOffset(0, 0, 0);
        boolean any = false;
        boolean all = true;
        for (int lz = 0; lz < SIDE; lz++) {
            for (int ly = 0; ly < SIDE; ly++) {
                int row = origin + ly * NODE_STRIDE_Y + lz * NODE_STRIDE_Z;
                int bits = 0;
                for (int lx = 0; lx < SIDE; lx++) {
                    if (v[row + lx] > ISO_LEVEL) {
                        bits |= 1 << lx;
                    }
                }
                rowMask[ly + lz * SIDE] = bits;
                any |= bits != 0;
                all &= bits == FULL_ROW;
            }
        }
        if (!any || all) {
            return;
        }
        nextTileSerial();
        slot = s;
        nodeOrigin = origin;
        tileNodeX = gx;
        tileNodeY = gy;
        tileNodeZ = gz;
        for (int cz = 0; cz < TILE_CELLS; cz++) {
            for (int cy = 0; cy < TILE_CELLS; cy++) {
                int r00 = rowMask[cy + cz * SIDE];
                int r10 = rowMask[cy + 1 + cz * SIDE];
                int r01 = rowMask[cy + (cz + 1) * SIDE];
                int r11 = rowMask[cy + 1 + (cz + 1) * SIDE];
                if ((r00 | r10 | r01 | r11) == 0 || (r00 & r10 & r01 & r11) == FULL_ROW) {
                    continue; // the whole row of cells is outside, or inside
                }
                for (int cx = 0; cx < TILE_CELLS; cx++) {
                    // Bit c of the cube index is corner c = x | y << 1 | z << 2.
                    int cube = ((r00 >>> cx) & 3)
                            | (((r10 >>> cx) & 3) << 2)
                            | (((r01 >>> cx) & 3) << 4)
                            | (((r11 >>> cx) & 3) << 6);
                    if (cube != 0 && cube != 0xFF) {
                        marchCell(cube, cx, cy, cz);
                    }
                }
            }
        }
    }

    /** Appends the triangles of one cell that survive culling, creating their vertices on first use. */
    private void marchCell(int cube, int cx, int cy, int cz) {
        int[] tris = TRI_TABLE[cube];
        int cellKey = ((cz * SIDE + cy) * SIDE + cx) * 3;
        int cellNode = nodeOrigin + cx + cy * NODE_STRIDE_Y + cz * NODE_STRIDE_Z;
        for (int j = 0; j + 2 < tris.length && tris[j] >= 0; j += 3) {
            for (int k = 0; k < 3; k++) {
                int e = tris[j + k];
                int key = cellKey + EDGE_KEY[e];
                cornerKey[k] = key;
                if (edgeStamp[key] == tileSerial) {
                    int existing = edgeVertex[key];
                    cornerVertex[k] = existing;
                    cornerX[k] = vx[existing];
                    cornerY[k] = vy[existing];
                    cornerZ[k] = vz[existing];
                } else {
                    cornerVertex[k] = -1;
                    locateCorner(k, e, cx, cy, cz, cellNode + EDGE_NODE[e]);
                }
            }
            if (culling && !visible()) {
                continue;
            }
            if (triangles.length < (triangleCount + 1) * 3) {
                growTriangles();
            }
            int base = triangleCount * 3;
            for (int k = 0; k < 3; k++) {
                int vertex = cornerVertex[k];
                if (vertex < 0) {
                    vertex = addVertex(k);
                    edgeStamp[cornerKey[k]] = tileSerial;
                    edgeVertex[cornerKey[k]] = vertex;
                }
                triangles[base + k] = vertex;
            }
            triangleCount++;
        }
    }

    /**
     * Computes the position of corner {@code k}, a new vertex on edge {@code e} of cell ({@code cx}, {@code cy},
     * {@code cz}) whose lower node has field index {@code node}. Always from the lower global node, with the same
     * arithmetic in every tile, so a border vertex comes out bit-identical in both tiles.
     */
    private void locateCorner(int k, int e, int cx, int cy, int cz, int node) {
        int axis = EDGE_AXIS[e];
        float t = edgeT(node, AXIS_STRIDE[axis]);
        float gx = tileNodeX + cx + EDGE_DX[e];
        float gy = tileNodeY + cy + EDGE_DY[e];
        float gz = tileNodeZ + cz + EDGE_DZ[e];
        if (axis == 0) {
            gx += t;
        } else if (axis == 1) {
            gy += t;
        } else {
            gz += t;
        }
        cornerNode[k] = node;
        cornerAxis[k] = axis;
        cornerT[k] = t;
        cornerGX[k] = gx;
        cornerGY[k] = gy;
        cornerGZ[k] = gz;
        cornerX[k] = gx * CELL_SIZE - offX;
        cornerY[k] = gy * CELL_SIZE - offY;
        cornerZ[k] = gz * CELL_SIZE - offZ;
    }

    /** Where the surface crosses the edge from {@code node} to {@code node + stride}, 0..1 from the lower node. */
    private float edgeT(int node, int stride) {
        float va = values[node];
        float vb = values[node + stride];
        float t = (ISO_LEVEL - va) / (vb - va);
        if (!(t >= MIN_EDGE_T)) {
            return MIN_EDGE_T; // also catches NaN, which a correct table never causes
        }
        return Math.min(t, 1f - MIN_EDGE_T);
    }

    /**
     * Whether the triangle in the corner arrays faces the camera (counter-clockwise as seen from the origin) and its
     * centroid is not too far behind the camera plane.
     */
    private boolean visible() {
        float x0 = cornerX[0];
        float y0 = cornerY[0];
        float z0 = cornerZ[0];
        float e1x = cornerX[1] - x0;
        float e1y = cornerY[1] - y0;
        float e1z = cornerZ[1] - z0;
        float e2x = cornerX[2] - x0;
        float e2y = cornerY[2] - y0;
        float e2z = cornerZ[2] - z0;
        float crossX = e1y * e2z - e1z * e2y;
        float crossY = e1z * e2x - e1x * e2z;
        float crossZ = e1x * e2y - e1y * e2x;
        if (crossX * x0 + crossY * y0 + crossZ * z0 >= 0f) {
            return false; // back-facing or edge-on
        }
        float depth = (cornerX[0] + cornerX[1] + cornerX[2]) * fwdX + (cornerY[0] + cornerY[1] + cornerY[2]) * fwdY
                + (cornerZ[0] + cornerZ[1] + cornerZ[2]) * fwdZ;
        return depth * (1f / 3f) >= -CULL_MARGIN;
    }

    /** Creates the vertex of corner {@code k} (located by {@link #locateCorner}): normal, thickness, ripples. */
    private int addVertex(int k) {
        if (vertexCount == vx.length) {
            growVertices();
        }
        float[] v = values;
        int a = cornerNode[k];
        int b = a + AXIS_STRIDE[cornerAxis[k]];
        float t = cornerT[k];
        // Central-difference gradients at both edge nodes (the 1 / (2 ds) scale cancels in the normalisation).
        float gax = v[a + 1] - v[a - 1];
        float gay = v[a + NODE_STRIDE_Y] - v[a - NODE_STRIDE_Y];
        float gaz = v[a + NODE_STRIDE_Z] - v[a - NODE_STRIDE_Z];
        float gbx = v[b + 1] - v[b - 1];
        float gby = v[b + NODE_STRIDE_Y] - v[b - NODE_STRIDE_Y];
        float gbz = v[b + NODE_STRIDE_Z] - v[b - NODE_STRIDE_Z];
        float gx = gax + (gbx - gax) * t;
        float gy = gay + (gby - gay) * t;
        float gz = gaz + (gbz - gaz) * t;
        float len2 = gx * gx + gy * gy + gz * gz;
        float nX;
        float nY;
        float nZ;
        if (len2 > MIN_GRADIENT_SQ && len2 < Float.POSITIVE_INFINITY) {
            // The density grows into the fluid, so the outward normal is the negative gradient.
            float inv = (float) (-1.0 / Math.sqrt(len2));
            nX = gx * inv;
            nY = gy * inv;
            nZ = gz * inv;
        } else {
            nX = 0f;
            nY = 1f;
            nZ = 0f;
        }
        float px = cornerGX[k];
        float py = cornerGY[k];
        float pz = cornerGZ[k];
        float thickness = field.thickness(slot, px - nX * THICKNESS_PROBE_CELLS, py - nY * THICKNESS_PROBE_CELLS,
                pz - nZ * THICKNESS_PROBE_CELLS);
        if (ripples && nY > RIPPLE_MIN_UP) {
            float impact = field.impact(slot, px, py, pz);
            if (impact > RIPPLE_MIN_IMPACT) {
                // Distance to the nearest impact source, r / R: equal to sqrt(1 - cbrt(I)) for a single source of
                // amount 1, but it stays meaningful under a strong stream, where I is far above 1.
                float radius = field.sourceDistance(slot, px, py, pz);
                float dX = field.sourceDistance(slot, px + RIPPLE_GRADIENT_CELLS, py, pz)
                        - field.sourceDistance(slot, px - RIPPLE_GRADIENT_CELLS, py, pz);
                float dZ = field.sourceDistance(slot, px, py, pz + RIPPLE_GRADIENT_CELLS)
                        - field.sourceDistance(slot, px, py, pz - RIPPLE_GRADIENT_CELLS);
                float len = (float) Math.sqrt(dX * dX + dZ * dZ);
                if (len > MIN_RIPPLE_GRADIENT) {
                    float strength = Math.min(impact, 1f);
                    // Rings travel outward as the clock advances.
                    double phase = TWO_PI * (rippleCycles - RIPPLE_RINGS * radius);
                    // radial = grad / |grad| of the distance points away from the source; the tilt fades on steep
                    // surfaces.
                    float push = RIPPLE_AMPLITUDE * strength * (float) Math.sin(phase) * nY / len;
                    nX += dX * push;
                    nZ += dZ * push;
                    // nY > RIPPLE_MIN_UP is unchanged, so the length is at least that: no division by zero.
                    float inv = (float) (1.0 / Math.sqrt(nX * nX + nY * nY + nZ * nZ));
                    nX *= inv;
                    nY *= inv;
                    nZ *= inv;
                }
            }
        }
        int index = vertexCount++;
        vx[index] = cornerX[k];
        vy[index] = cornerY[k];
        vz[index] = cornerZ[k];
        nx[index] = nX;
        ny[index] = nY;
        nz[index] = nZ;
        thick[index] = thickness;
        vmat[index] = material;
        return index;
    }

    /** Sorts every triangle appended since begin() far to near (radix sort on centroid distance). Returns the count. */
    public int sort() {
        int n = triangleCount;
        if (order.length < n) {
            int size = Math.max(n, order.length * 2);
            order = new int[size];
            orderTmp = new int[size];
            keys = new int[size];
            keysTmp = new int[size];
        }
        for (int t = 0; t < n; t++) {
            int a = triangles[t * 3];
            int b = triangles[t * 3 + 1];
            int c = triangles[t * 3 + 2];
            float x = (vx[a] + vx[b] + vx[c]) * (1f / 3f);
            float y = (vy[a] + vy[b] + vy[c]) * (1f / 3f);
            float z = (vz[a] + vz[b] + vz[c]) * (1f / 3f);
            int q = (int) ((float) Math.sqrt(x * x + y * y + z * z) * SORT_STEPS_PER_BLOCK);
            // Ascending keys put the farthest triangle first.
            keys[t] = SORT_KEY_MAX - Math.min(q, SORT_KEY_MAX);
            order[t] = t;
        }
        radixPass(keys, order, keysTmp, orderTmp, n, 0);
        radixPass(keysTmp, orderTmp, keys, order, n, 8);
        sorted = true;
        return n;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Camera-relative position of vertex {@code v}. */
    public float vertexX(int v) {
        return vx[v];
    }

    public float vertexY(int v) {
        return vy[v];
    }

    public float vertexZ(int v) {
        return vz[v];
    }

    /** Unit outward normal of vertex {@code v}; never NaN. */
    public float normalX(int v) {
        return nx[v];
    }

    public float normalY(int v) {
        return ny[v];
    }

    public float normalZ(int v) {
        return nz[v];
    }

    /** 0 = thin edge / droplet, 1 = deep pool (Beer-Lambert input, Plan.md §8.4.5). */
    public float thickness(int v) {
        return thick[v];
    }

    public byte vertexMaterial(int v) {
        return vmat[v];
    }

    public int triangleCount() {
        return triangleCount;
    }

    /** Vertex index of corner k (0..2) of the t-th triangle; after sort() t runs far to near. CCW seen from outside. */
    public int triangleVertex(int t, int k) {
        return triangles[(sorted ? order[t] : t) * 3 + k];
    }

    /**
     * Tiles meshed since begin() (for stats): every tile of the meshed fields that was not culled as lying entirely
     * behind the camera, including tiles that turned out to hold no surface.
     */
    public int lastTileCount() {
        return tileCountSinceBegin;
    }

    /** Starts a new edge cache generation; entries of earlier tiles become invalid without clearing. */
    private void nextTileSerial() {
        if (tileSerial == Integer.MAX_VALUE) {
            Arrays.fill(edgeStamp, 0);
            tileSerial = 0;
        }
        tileSerial++;
    }

    /** One pass of a stable LSD radix sort over 8 bits of the keys, carrying the triangle indices along. */
    private void radixPass(int[] srcKeys, int[] srcOrder, int[] dstKeys, int[] dstOrder, int count, int shift) {
        int[] bucket = radixCount;
        Arrays.fill(bucket, 0);
        for (int k = 0; k < count; k++) {
            bucket[(srcKeys[k] >>> shift) & 0xFF]++;
        }
        int sum = 0;
        for (int b = 0; b < bucket.length; b++) {
            int c = bucket[b];
            bucket[b] = sum;
            sum += c;
        }
        for (int k = 0; k < count; k++) {
            int key = srcKeys[k];
            int dst = bucket[(key >>> shift) & 0xFF]++;
            dstKeys[dst] = key;
            dstOrder[dst] = srcOrder[k];
        }
    }

    /** Doubles the vertex arrays, keeping their contents (only while the largest frame grows). */
    private void growVertices() {
        int size = Math.max(INITIAL_VERTICES, vx.length * 2);
        vx = Arrays.copyOf(vx, size);
        vy = Arrays.copyOf(vy, size);
        vz = Arrays.copyOf(vz, size);
        nx = Arrays.copyOf(nx, size);
        ny = Arrays.copyOf(ny, size);
        nz = Arrays.copyOf(nz, size);
        thick = Arrays.copyOf(thick, size);
        vmat = Arrays.copyOf(vmat, size);
    }

    /** Doubles the triangle array, keeping its contents. */
    private void growTriangles() {
        triangles = Arrays.copyOf(triangles, Math.max(INITIAL_TRIANGLES * 3, triangles.length * 2));
    }
}
