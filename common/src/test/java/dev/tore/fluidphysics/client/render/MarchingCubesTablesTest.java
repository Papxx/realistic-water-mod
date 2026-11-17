// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.client.render.MarchingCubesTables.CORNER_X;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.CORNER_Y;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.CORNER_Z;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.EDGE_AXIS;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.EDGE_END;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.EDGE_START;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.EDGE_TABLE;
import static dev.tore.fluidphysics.client.render.MarchingCubesTables.TRI_TABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the generated {@link MarchingCubesTables}: the documented corner and edge numbering, rows that use exactly
 * the crossing edges, identical cuts on every face two cubes share, and closed surfaces with outward normals on random
 * fields. {@code tools/generate_marching_cubes_tables.py} runs the same checks before it writes the tables; this test
 * keeps a hand edit or a broken regeneration from slipping through.
 */
class MarchingCubesTablesTest {
    /** Iso level of the random fields; a node is inside when its value is strictly greater (as in the mesher). */
    private static final float ISO = 0.5f;
    /**
     * Nodes per axis of a random field. The outer layer is forced outside, so every surface is closed, and the free
     * block of 4 x 4 x 4 nodes is too small for fluid inside a cavity inside fluid: a surface either bounds fluid
     * against the exterior or walls in an enclosed outside cavity.
     */
    private static final int GRID = 6;
    private static final int GRIDS = 400;
    private static final int MAX_TRIANGLES = 5;
    private static final int ROW_LENGTH = 16;
    /**
     * The fixed face rule keeps diagonal inside corners of a face apart, so outside nodes connect across face diagonals
     * as well as along edges: the outside is 18-connected.
     */
    private static final int[][] NEIGHBORS_18 = neighbors18();

    @Test
    void cornersAndEdgesFollowTheDocumentedNumbering() {
        assertEquals(8, CORNER_X.length);
        assertEquals(8, CORNER_Y.length);
        assertEquals(8, CORNER_Z.length);
        for (int c = 0; c < 8; c++) {
            assertEquals(c & 1, CORNER_X[c], "x of corner " + c);
            assertEquals((c >> 1) & 1, CORNER_Y[c], "y of corner " + c);
            assertEquals((c >> 2) & 1, CORNER_Z[c], "z of corner " + c);
        }
        assertEquals(12, EDGE_START.length);
        assertEquals(12, EDGE_END.length);
        assertEquals(12, EDGE_AXIS.length);
        for (int e = 0; e < 12; e++) {
            int axis = EDGE_AXIS[e];
            int start = EDGE_START[e];
            assertEquals(e / 4, axis, "edges 0-3 run along x, 4-7 along y, 8-11 along z");
            assertEquals(0, (start >> axis) & 1, "edge " + e + " must start at its lower end");
            assertEquals(start | (1 << axis), EDGE_END[e], "end of edge " + e);
            // The start corner's two other coordinates run (0,0), (1,0), (0,1), (1,1), the lower axis first.
            int first = axis == 0 ? 1 : 0;
            int second = axis == 2 ? 1 : 2;
            int k = e % 4;
            assertEquals(((k & 1) << first) | ((k >> 1) << second), start, "start of edge " + e);
        }
    }

    @Test
    void edgeTableMarksExactlyTheCrossingEdges() {
        assertEquals(256, EDGE_TABLE.length);
        for (int config = 0; config < 256; config++) {
            int expected = 0;
            for (int e = 0; e < 12; e++) {
                if (inside(config, EDGE_START[e]) != inside(config, EDGE_END[e])) {
                    expected |= 1 << e;
                }
            }
            assertEquals(expected, EDGE_TABLE[config], "edge mask of config " + config);
        }
    }

    @Test
    void everyRowIsTerminatedAndUsesExactlyTheCrossingEdges() {
        assertEquals(256, TRI_TABLE.length);
        for (int config = 0; config < 256; config++) {
            int[] row = TRI_TABLE[config];
            assertEquals(ROW_LENGTH, row.length, "length of row " + config);
            int used = rowLength(row);
            assertEquals(0, used % 3, "row " + config + " must hold whole triangles");
            assertTrue(used / 3 <= MAX_TRIANGLES, "config " + config + " has " + used / 3 + " triangles");
            for (int i = used; i < ROW_LENGTH; i++) {
                assertEquals(-1, row[i], "row " + config + " must end in -1");
            }
            int edges = 0;
            for (int i = 0; i < used; i += 3) {
                assertTrue(row[i] != row[i + 1] && row[i + 1] != row[i + 2] && row[i] != row[i + 2],
                        "degenerate triangle in config " + config);
                for (int k = 0; k < 3; k++) {
                    int e = row[i + k];
                    assertTrue(e < 12, "edge index " + e + " in config " + config);
                    assertEquals(1, (EDGE_TABLE[config] >> e) & 1, "config " + config + " uses edge " + e
                            + ", which does not cross");
                    edges |= 1 << e;
                }
            }
            assertEquals(EDGE_TABLE[config], edges, "config " + config + " must use every crossing edge");
        }
        assertEquals(0, rowLength(TRI_TABLE[0]), "all outside: no surface");
        assertEquals(0, rowLength(TRI_TABLE[255]), "all inside: no surface");
    }

    @Test
    void cubesSharingAFaceCutItTheSameWay() {
        int pairs = 0;
        for (int axis = 0; axis < 3; axis++) {
            int[] offset = new int[3];
            offset[axis] = 1;
            for (int lower = 0; lower < 256; lower++) {
                for (int upper = 0; upper < 256; upper++) {
                    if (!sameFace(lower, upper, axis)) {
                        continue;
                    }
                    pairs++;
                    // The upper cube must run every segment of the shared face backwards (consistent orientation).
                    assertEquals(openEdgesOnFace(lower, axis, 1, new int[3], false),
                            openEdgesOnFace(upper, axis, 0, offset, true),
                            "configs " + lower + " and " + upper + " cut their shared face (axis " + axis
                                    + ") differently");
                }
            }
        }
        assertEquals(3 * 256 * 16, pairs, "every configuration meets 16 neighbours per axis");
    }

    @Test
    void randomFieldsGiveClosedSurfacesWithOutwardNormals() {
        Random random = new Random(20261117L);
        int surfaces = 0;
        int cavities = 0;
        for (int grid = 0; grid < GRIDS; grid++) {
            int[] found = checkField(randomField(random, grid % 4), "field " + grid);
            surfaces += found[0];
            cavities += found[1];
        }
        assertTrue(surfaces > GRIDS, "only " + surfaces + " surfaces in " + GRIDS + " fields");
        assertTrue(cavities > 0, "no field enclosed an outside cavity");
    }

    /**
     * A field with its outer node layer at 0. Kinds 0 and 1: uniform values; kind 2: multiples of 1/8, so many nodes
     * sit exactly at the iso level (outside, the surface touches them); kind 3: mostly inside, which encloses cavities.
     */
    private static float[] randomField(Random random, int kind) {
        float[] values = new float[GRID * GRID * GRID];
        for (int x = 1; x < GRID - 1; x++) {
            for (int y = 1; y < GRID - 1; y++) {
                for (int z = 1; z < GRID - 1; z++) {
                    values[node(x, y, z)] = switch (kind) {
                        case 2 -> random.nextInt(9) / 8f;
                        case 3 -> random.nextFloat() < 0.85f
                                ? 0.6f + 0.4f * random.nextFloat()
                                : 0.4f * random.nextFloat();
                        default -> random.nextFloat();
                    };
                }
            }
        }
        return values;
    }

    /**
     * Marches one field with the tables, a vertex named by its global edge (lower node and axis) rather than its
     * position, and checks the surface: closed and consistently oriented, and every connected surface encloses a
     * positive volume (normals out of the fluid) unless it walls in a cavity. Returns {surfaces, cavity walls}.
     */
    private static int[] checkField(float[] values, String name) {
        int cells = GRID - 1;
        int[] triangles = new int[cells * cells * cells * MAX_TRIANGLES * 3];
        int count = 0;
        for (int x = 0; x < cells; x++) {
            for (int y = 0; y < cells; y++) {
                for (int z = 0; z < cells; z++) {
                    int config = 0;
                    for (int c = 0; c < 8; c++) {
                        if (values[node(x + CORNER_X[c], y + CORNER_Y[c], z + CORNER_Z[c])] > ISO) {
                            config |= 1 << c;
                        }
                    }
                    int[] row = TRI_TABLE[config];
                    for (int i = 0; i < ROW_LENGTH && row[i] >= 0; i++) {
                        triangles[count++] = vertex(x, y, z, row[i]);
                    }
                }
            }
        }

        // Closed and consistently oriented: every directed edge appears once and its reverse once.
        Map<Long, Integer> directed = new HashMap<>();
        for (int i = 0; i < count; i += 3) {
            for (int k = 0; k < 3; k++) {
                directed.merge(pair(triangles[i + k], triangles[i + (k + 1) % 3]), 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> entry : directed.entrySet()) {
            long key = entry.getKey();
            assertEquals(1, entry.getValue(), name + ": a directed edge is used twice");
            assertEquals(1, directed.getOrDefault(pair(key & 0xFFFFFFFFL, key >>> 32), 0),
                    name + ": the surface is open or flips its orientation");
        }

        int[] parent = new int[GRID * GRID * GRID * 3];
        for (int v = 0; v < parent.length; v++) {
            parent[v] = v;
        }
        for (int i = 0; i < count; i += 3) {
            union(parent, triangles[i], triangles[i + 1]);
            union(parent, triangles[i], triangles[i + 2]);
        }
        boolean[] exterior = exterior(values);
        double[] volume = new double[parent.length];
        double[][] reference = new double[parent.length][];
        byte[] side = new byte[parent.length]; // 0 = no surface, 1 = bounds the exterior, 2 = walls in a cavity
        for (int i = 0; i < count; i += 3) {
            int root = find(parent, triangles[i]);
            if (reference[root] == null) {
                // Volumes relative to a point of the surface stay accurate for tiny droplets far from the origin.
                reference[root] = position(values, triangles[i]);
            }
            double[] r = reference[root];
            double[] a = position(values, triangles[i]);
            double[] b = position(values, triangles[i + 1]);
            double[] c = position(values, triangles[i + 2]);
            volume[root] += triple(a[0] - r[0], a[1] - r[1], a[2] - r[2], b[0] - r[0], b[1] - r[1], b[2] - r[2],
                    c[0] - r[0], c[1] - r[1], c[2] - r[2]);
            for (int k = 0; k < 3; k++) {
                byte s = exterior[outsideNode(values, triangles[i + k], name)] ? (byte) 1 : (byte) 2;
                assertTrue(side[root] == 0 || side[root] == s, name + ": a surface touches the exterior and a cavity");
                side[root] = s;
            }
        }

        int surfaces = 0;
        int cavities = 0;
        for (int root = 0; root < parent.length; root++) {
            if (side[root] == 0) {
                continue;
            }
            surfaces++;
            if (side[root] == 1) {
                assertTrue(volume[root] > 0.0, name + ": a surface encloses the volume " + volume[root]
                        + ", its normals point into the fluid");
            } else {
                cavities++;
                // Zero when every outside node of the cavity sits exactly at the iso level: the wall collapses onto
                // those nodes.
                assertTrue(volume[root] <= 0.0, name + ": a cavity wall encloses the volume " + volume[root]
                        + ", its normals point into the fluid");
            }
        }
        return new int[] {surfaces, cavities};
    }

    /** Outside nodes reachable from the (outside) boundary through the 18-neighbourhood. */
    private static boolean[] exterior(float[] values) {
        boolean[] reached = new boolean[values.length];
        int[] queue = new int[values.length];
        int head = 0;
        int tail = 0;
        reached[0] = true;
        queue[tail++] = 0;
        while (head < tail) {
            int n = queue[head++];
            int x = n / (GRID * GRID);
            int y = n / GRID % GRID;
            int z = n % GRID;
            for (int[] d : NEIGHBORS_18) {
                int mx = x + d[0];
                int my = y + d[1];
                int mz = z + d[2];
                if (mx < 0 || my < 0 || mz < 0 || mx >= GRID || my >= GRID || mz >= GRID) {
                    continue;
                }
                int m = node(mx, my, mz);
                if (!reached[m] && values[m] <= ISO) {
                    reached[m] = true;
                    queue[tail++] = m;
                }
            }
        }
        return reached;
    }

    private static int[][] neighbors18() {
        int[][] result = new int[18][];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int steps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (steps == 1 || steps == 2) {
                        result[n++] = new int[] {dx, dy, dz};
                    }
                }
            }
        }
        return result;
    }

    /** Position of a vertex: linear interpolation of the field along its edge. */
    private static double[] position(float[] values, int vertex) {
        int axis = vertex % 3;
        int n = vertex / 3;
        double va = values[n];
        double vb = values[n + stride(axis)];
        double t = (ISO - va) / (vb - va);
        double[] p = {n / (GRID * GRID), n / GRID % GRID, n % GRID};
        p[axis] += t;
        return p;
    }

    /** The outside node of a vertex's edge; fails if the edge does not cross the surface. */
    private static int outsideNode(float[] values, int vertex, String name) {
        int n = vertex / 3;
        int m = n + stride(vertex % 3);
        assertTrue((values[n] > ISO) != (values[m] > ISO), name + ": a vertex lies on an edge that does not cross");
        return values[n] > ISO ? m : n;
    }

    /** Global name of edge {@code e} of the cell at {@code (x, y, z)}: its start node and axis. */
    private static int vertex(int x, int y, int z, int e) {
        int s = EDGE_START[e];
        return node(x + CORNER_X[s], y + CORNER_Y[s], z + CORNER_Z[s]) * 3 + EDGE_AXIS[e];
    }

    private static int node(int x, int y, int z) {
        return (x * GRID + y) * GRID + z;
    }

    private static int stride(int axis) {
        return axis == 0 ? GRID * GRID : axis == 1 ? GRID : 1;
    }

    /** Whether the lower cube's upper face along {@code axis} has the same corners as the upper cube's lower face. */
    private static boolean sameFace(int lower, int upper, int axis) {
        for (int c = 0; c < 8; c++) {
            if (((c >> axis) & 1) == 1 && inside(lower, c) != inside(upper, c ^ (1 << axis))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The triangle edges of one configuration that only one of its triangles uses (the open boundary of the cell's
     * surface) and that lie on the face {@code side} along {@code axis}, as directed pairs of global edge names of a
     * cell at {@code offset}; {@code reversed} flips their direction.
     */
    private static Set<Long> openEdgesOnFace(int config, int axis, int side, int[] offset, boolean reversed) {
        int[] row = TRI_TABLE[config];
        int used = rowLength(row);
        Set<Long> directed = new HashSet<>();
        for (int i = 0; i < used; i += 3) {
            for (int k = 0; k < 3; k++) {
                directed.add(pair(row[i + k], row[i + (k + 1) % 3]));
            }
        }
        Set<Long> result = new HashSet<>();
        for (int i = 0; i < used; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = row[i + k];
                int b = row[i + (k + 1) % 3];
                if (!directed.contains(pair(b, a)) && onFace(a, axis, side) && onFace(b, axis, side)) {
                    long ga = globalEdge(a, offset);
                    long gb = globalEdge(b, offset);
                    result.add(reversed ? pair(gb, ga) : pair(ga, gb));
                }
            }
        }
        return result;
    }

    private static boolean onFace(int edge, int axis, int side) {
        return EDGE_AXIS[edge] != axis && ((EDGE_START[edge] >> axis) & 1) == side;
    }

    /** Name of an edge of the cell at {@code offset} (coordinates 0..1) in a 3 x 3 x 3 node block. */
    private static int globalEdge(int edge, int[] offset) {
        int s = EDGE_START[edge];
        int x = CORNER_X[s] + offset[0];
        int y = CORNER_Y[s] + offset[1];
        int z = CORNER_Z[s] + offset[2];
        return ((x * 3 + y) * 3 + z) * 3 + EDGE_AXIS[edge];
    }

    private static int rowLength(int[] row) {
        int n = 0;
        while (n < row.length && row[n] >= 0) {
            n++;
        }
        return n;
    }

    private static boolean inside(int config, int corner) {
        return ((config >> corner) & 1) == 1;
    }

    private static long pair(long a, long b) {
        return (a << 32) | b;
    }

    private static double triple(double ax, double ay, double az, double bx, double by, double bz, double cx,
                                 double cy, double cz) {
        return ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx);
    }

    private static int find(int[] parent, int v) {
        while (parent[v] != v) {
            parent[v] = parent[parent[v]];
            v = parent[v];
        }
        return v;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
