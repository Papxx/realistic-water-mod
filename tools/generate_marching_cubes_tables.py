# SPDX-License-Identifier: Apache-2.0
"""Generates the marching cubes tables of the smooth fluid surface (Plan.md §8.2 and §8.4.4).

Run from the repository root:  python3 tools/generate_marching_cubes_tables.py
Writes common/src/main/java/dev/tore/fluidphysics/client/render/MarchingCubesTables.java. Standard library only.

Clean-room: the tables are derived here from first principles; nothing is copied from published marching cubes
tables. Deterministic: the same script always writes the same bytes, so the Java file only changes when this script
does.

For each of the 256 corner configurations (bit c set = corner c inside, i.e. its value is greater than the iso level):
1. Every cube face is cut by straight segments joining the crossing points on its edges. A face whose two inside
   corners sit on a diagonal is ambiguous; ONE fixed rule decides it for every cube: the inside corners stay separate,
   each is cut off by its own segment. The rule only looks at the four corners of that face, so the two cubes sharing
   a face always cut it the same way and the mesh has no cracks (the classic table mixes in complement symmetry, which
   breaks exactly this; Plan.md §8.4.4).
2. Each segment is oriented so that the inside part of its face lies on its left, seen from outside the cube (walking
   counter-clockwise around the face, a segment runs from the point where the walk leaves the inside to the point
   where it re-enters it). Every crossing edge lies on two faces, so the segments chain into closed loops: the
   boundary curves of the inside regions of the cube surface, oriented as such.
3. Each loop is capped with triangles. The cap and the inside part of the cube surface enclose the fluid in the cube,
   so the cap's outward side runs against the loop: the cap polygon is the reversed loop, which makes its triangles
   counter-clockwise seen from outside the fluid. Of all triangulations of the cap polygon the script picks the one
   whose worst triangle is best aligned with the loop (judged at the edge midpoints, ties go to better-shaped
   triangles, then to the first in a fixed order; a folded triangle aborts) and never uses a chord whose two ends lie
   on the same cube face (it would lie in that face and be shared with the neighbouring cube).

Before writing, the script checks the tables and aborts without writing anything if a check fails:
- per configuration: only crossing edges, every crossing edge used, no degenerate triangle, at most 5 triangles, and
  the open boundary of the triangles is exactly the face segments;
- every pair of cubes sharing a face (all 3 axes, all matching configurations) produces opposite, matching segments
  on that face;
- random grids with the outer nodes forced outside: marched surfaces are closed and consistently oriented (every
  directed triangle edge appears once, its reverse once), every surface component separates one 6-connected inside
  region from one 18-connected outside region, and its exact signed volume is positive (negative for the walls of
  enclosed outside cavities), i.e. the normals point out of the fluid.
"""

import os
import random
import sys
from fractions import Fraction
from functools import lru_cache

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUTPUT = os.path.join(ROOT, "common", "src", "main", "java", "dev", "tore", "fluidphysics", "client", "render",
                      "MarchingCubesTables.java")

MAX_TRIANGLES = 5
ROW_LENGTH = 16

# --- Cube geometry ----------------------------------------------------------------------------------------------------

# Corner c sits at offset (c & 1, (c >> 1) & 1, (c >> 2) & 1).
CORNER_X = [c & 1 for c in range(8)]
CORNER_Y = [(c >> 1) & 1 for c in range(8)]
CORNER_Z = [(c >> 2) & 1 for c in range(8)]
CORNER_COORDS = [(CORNER_X[c], CORNER_Y[c], CORNER_Z[c]) for c in range(8)]


def corner_index(coords):
    return coords[0] | (coords[1] << 1) | (coords[2] << 2)


def build_edges():
    """Edges 0-3 run along x, 4-7 along y, 8-11 along z. Within a group the start corner's two other coordinates run
    (0,0), (1,0), (0,1), (1,1), the lower axis first; the start corner is the lower-coordinate end."""
    starts, axes = [], []
    for axis in range(3):
        first, second = [a for a in range(3) if a != axis]
        for b in (0, 1):
            for a in (0, 1):
                starts.append((a << first) | (b << second))
                axes.append(axis)
    return starts, axes


EDGE_START, EDGE_AXIS = build_edges()
EDGE_END = [EDGE_START[e] | (1 << EDGE_AXIS[e]) for e in range(12)]
EDGE_BETWEEN = {}
for _e in range(12):
    EDGE_BETWEEN[(EDGE_START[_e], EDGE_END[_e])] = _e
    EDGE_BETWEEN[(EDGE_END[_e], EDGE_START[_e])] = _e
EDGE_MIDPOINT = []
for _e in range(12):
    _p = [float(v) for v in CORNER_COORDS[EDGE_START[_e]]]
    _p[EDGE_AXIS[_e]] += 0.5
    EDGE_MIDPOINT.append(tuple(_p))


def build_faces():
    """The 6 faces as rings of 4 corners, counter-clockwise seen from outside the cube (against the face normal).

    Face (axis a, side s) has the outward normal (2s - 1) * e_a. With b = a + 1 and c = a + 2 (mod 3), e_b x e_c = e_a,
    so the ring (0,0), (1,0), (1,1), (0,1) in (b, c) is counter-clockwise seen from +a; the -a face runs the other way.
    """
    rings = []
    for axis in range(3):
        b, c = (axis + 1) % 3, (axis + 2) % 3
        for side in (0, 1):
            order = [(0, 0), (1, 0), (1, 1), (0, 1)] if side == 1 else [(0, 0), (0, 1), (1, 1), (1, 0)]
            ring = []
            for u, v in order:
                coords = [0, 0, 0]
                coords[axis], coords[b], coords[c] = side, u, v
                ring.append(corner_index(coords))
            rings.append((axis, side, ring))
    return rings


FACES = build_faces()
FACE_EDGES = [frozenset(EDGE_BETWEEN[(ring[i], ring[(i + 1) % 4])] for i in range(4)) for _, _, ring in FACES]
FACES_OF_EDGE = [frozenset(f for f in range(6) if e in FACE_EDGES[f]) for e in range(12)]


class TableError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise TableError(message)


def is_inside(config, corner):
    return (config >> corner) & 1 == 1


def crossing_edges(config):
    return [e for e in range(12) if is_inside(config, EDGE_START[e]) != is_inside(config, EDGE_END[e])]


def edge_mask(config):
    mask = 0
    for e in crossing_edges(config):
        mask |= 1 << e
    return mask


# --- Step 1 + 2: face segments and boundary loops ---------------------------------------------------------------------

def face_segments(config, ring):
    """Oriented segments (exit edge, entry edge) cutting one face, inside part on the left seen from outside."""
    flags = [is_inside(config, corner) for corner in ring]
    count = sum(flags)
    if count in (0, 4):
        return []

    def ring_edge(i):  # edge from ring corner i to ring corner i + 1
        return EDGE_BETWEEN[(ring[i % 4], ring[(i + 1) % 4])]

    if count == 2 and flags[0] == flags[2]:
        # Ambiguous face, inside corners on a diagonal: each inside corner is cut off on its own. Walking
        # counter-clockwise, the walk enters it on the edge before it and leaves it on the edge after it.
        return [(ring_edge(i), ring_edge(i - 1)) for i in range(4) if flags[i]]
    exits = [ring_edge(i) for i in range(4) if flags[i] and not flags[(i + 1) % 4]]
    entries = [ring_edge(i) for i in range(4) if not flags[i] and flags[(i + 1) % 4]]
    require(len(exits) == 1 and len(entries) == 1, "config %d: a face has %d exits" % (config, len(exits)))
    return [(exits[0], entries[0])]


def all_face_segments(config):
    return [segment for _, _, ring in FACES for segment in face_segments(config, ring)]


def boundary_loops(config):
    """Chains the face segments into closed loops of edge indices, each starting at its smallest edge."""
    following = {}
    for start, end in all_face_segments(config):
        require(start not in following, "config %d: edge %d starts two segments" % (config, start))
        following[start] = end
    crossing = crossing_edges(config)
    require(sorted(following) == crossing, "config %d: segment starts are not the crossing edges" % config)
    require(sorted(following.values()) == crossing, "config %d: segment ends are not the crossing edges" % config)
    loops, seen = [], set()
    for first in crossing:
        if first in seen:
            continue
        loop, edge = [], first
        while edge not in seen:
            seen.add(edge)
            loop.append(edge)
            edge = following[edge]
        require(edge == first, "config %d: segments do not close into a loop" % config)
        loops.append(loop)
    return loops


# --- Step 3: capping the loops ----------------------------------------------------------------------------------------

def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def length(a):
    return dot(a, a) ** 0.5


@lru_cache(maxsize=None)
def triangulations(first, last):
    """All triangulations of the polygon chain first..last (closed by the side last -> first), as tuples of index
    triples (i, k, j) with i < k < j, which keep the polygon's winding. Enumeration order is fixed."""
    if last - first < 2:
        return ((),)
    result = []
    for k in range(first + 1, last):
        for left in triangulations(first, k):
            for right in triangulations(k, last):
                result.append(left + ((first, k, last),) + right)
    return tuple(result)


def cap_triangles(loop):
    """Triangles closing one boundary loop, counter-clockwise seen from outside the fluid."""
    polygon = [loop[0]] + loop[:0:-1]  # the reversed loop, still starting at its smallest edge
    n = len(polygon)
    points = [EDGE_MIDPOINT[e] for e in polygon]
    # Vector area of the polygon (independent of the triangulation): the direction the cap should face.
    area = (0.0, 0.0, 0.0)
    for i in range(n):
        c = cross(points[i], points[(i + 1) % n])
        area = (area[0] + c[0], area[1] + c[1], area[2] + c[2])
    area_length = length(area)

    def chord_allowed(i, j):
        if abs(i - j) == 1 or abs(i - j) == n - 1:
            return True  # a side of the polygon, i.e. a face segment
        return not (FACES_OF_EDGE[polygon[i]] & FACES_OF_EDGE[polygon[j]])

    best_key, best = None, None
    for triangulation in triangulations(0, n - 1):
        if not all(chord_allowed(i, k) and chord_allowed(k, j) and chord_allowed(i, j)
                   for i, k, j in triangulation):
            continue
        min_alignment, min_quality = 1.0, 1.0
        for i, k, j in triangulation:
            a, b, c = points[i], points[k], points[j]
            normal = cross(sub(b, a), sub(c, a))
            normal_length = length(normal)
            if normal_length == 0.0:
                min_alignment, min_quality = -2.0, 0.0
                break
            alignment = dot(normal, area) / (normal_length * area_length) if area_length > 0.0 else 0.0
            # Shape quality 4 sqrt(3) A / (sum of squared sides): 1 for an equilateral triangle, 0 for a sliver.
            quality = 2.0 * 3.0 ** 0.5 * normal_length / (
                dot(sub(b, a), sub(b, a)) + dot(sub(c, b), sub(c, b)) + dot(sub(a, c), sub(a, c)))
            min_alignment = min(min_alignment, alignment)
            min_quality = min(min_quality, quality)
        key = (round(min_alignment, 9), round(min_quality, 9))
        if best_key is None or key > best_key:  # strictly better only: ties keep the first in enumeration order
            best_key, best = key, triangulation
    require(best is not None, "loop %s has no triangulation without chords in a face" % loop)
    require(best_key[0] > 0.0, "loop %s can only be capped with folded triangles" % loop)
    return [(polygon[i], polygon[k], polygon[j]) for i, k, j in best], best_key[0]


def generate():
    edge_table, tri_table = [], []
    worst_alignment = 1.0
    for config in range(256):
        triangles = []
        for loop in boundary_loops(config):
            cap, alignment = cap_triangles(loop)
            triangles.extend(cap)
            worst_alignment = min(worst_alignment, alignment)
        require(len(triangles) <= MAX_TRIANGLES, "config %d needs %d triangles" % (config, len(triangles)))
        edge_table.append(edge_mask(config))
        tri_table.append(triangles)
    return edge_table, tri_table, worst_alignment


# --- Self-check 1: every configuration on its own ---------------------------------------------------------------------

def directed_edges(triangles):
    counts = {}
    for a, b, c in triangles:
        for edge in ((a, b), (b, c), (c, a)):
            counts[edge] = counts.get(edge, 0) + 1
    return counts


def check_configs(edge_table, tri_table):
    for config in range(256):
        triangles = tri_table[config]
        crossing = set(crossing_edges(config))
        require(edge_table[config] == edge_mask(config), "config %d: wrong edge mask" % config)
        if config in (0, 255):
            require(not triangles, "config %d must be empty" % config)
        require(len(triangles) <= MAX_TRIANGLES, "config %d has too many triangles" % config)
        used = set()
        for triangle in triangles:
            require(len(set(triangle)) == 3, "config %d: degenerate triangle %s" % (config, triangle))
            require(set(triangle) <= crossing, "config %d: triangle %s uses a non-crossing edge" % (config, triangle))
            used.update(triangle)
        require(used == crossing, "config %d: not every crossing edge is used" % config)
        counts = directed_edges(triangles)
        require(all(count == 1 for count in counts.values()), "config %d: a directed edge repeats" % config)
        boundary = {edge for edge in counts if (edge[1], edge[0]) not in counts}
        # The caps run against the loops, so their open boundary is every face segment, reversed.
        expected = {(end, start) for start, end in all_face_segments(config)}
        require(boundary == expected, "config %d: open boundary differs from the face segments" % config)


# --- Self-check 2: neighbouring cubes cut their shared face the same way ----------------------------------------------

def global_edge(offset, e):
    s = CORNER_COORDS[EDGE_START[e]]
    return (s[0] + offset[0], s[1] + offset[1], s[2] + offset[2], EDGE_AXIS[e])


def face_boundary(config, face, offset):
    """Open boundary edges of the config's triangles that lie on one face, as global edges."""
    counts = directed_edges(MC_TRI[config])
    result = set()
    for a, b in counts:
        if (b, a) not in counts and a in FACE_EDGES[face] and b in FACE_EDGES[face]:
            result.add((global_edge(offset, a), global_edge(offset, b)))
    return result


def check_shared_faces():
    pairs, ambiguous = 0, 0
    for axis in range(3):
        high_face = 2 * axis + 1  # face (axis, side 1) of the lower cube ...
        low_face = 2 * axis       # ... is face (axis, side 0) of the upper cube
        offset = [0, 0, 0]
        offset[axis] = 1
        face_corners = [c for c in range(8) if (c >> axis) & 1]
        ring = FACES[high_face][2]
        for lower in range(256):
            for upper in range(256):
                if any(is_inside(lower, c) != is_inside(upper, c ^ (1 << axis)) for c in face_corners):
                    continue
                pairs += 1
                flags = [is_inside(lower, c) for c in ring]
                if sum(flags) == 2 and flags[0] == flags[2]:
                    ambiguous += 1
                mine = face_boundary(lower, high_face, (0, 0, 0))
                theirs = face_boundary(upper, low_face, tuple(offset))
                require(mine == {(b, a) for a, b in theirs},
                        "configs %d and %d cut their shared face (axis %d) differently" % (lower, upper, axis))
    return pairs, ambiguous


# --- Self-check 3: closed, outward-oriented surfaces on random grids --------------------------------------------------

GRID = 6  # nodes per axis; the outer layer is forced outside, so a 4x4x4 block of free nodes remains
ISO = 500  # integer field values keep the interpolated positions exact rationals


class UnionFind:
    def __init__(self):
        self.parent = {}

    def find(self, a):
        self.parent.setdefault(a, a)
        root = a
        while self.parent[root] != root:
            root = self.parent[root]
        while self.parent[a] != root:
            self.parent[a], a = root, self.parent[a]
        return root

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[max(ra, rb)] = min(ra, rb)


def node_index(x, y, z):
    return (x * GRID + y) * GRID + z


def node_coords(n):
    return n // (GRID * GRID), (n // GRID) % GRID, n % GRID


NEIGHBORS_6 = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
NEIGHBORS_18 = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
                if 1 <= abs(dx) + abs(dy) + abs(dz) <= 2]


def node_components(inside, want_inside, neighbors):
    components = UnionFind()
    for n in range(GRID ** 3):
        if inside[n] != want_inside:
            continue
        components.find(n)
        x, y, z = node_coords(n)
        for dx, dy, dz in neighbors:
            mx, my, mz = x + dx, y + dy, z + dz
            if 0 <= mx < GRID and 0 <= my < GRID and 0 <= mz < GRID and inside[node_index(mx, my, mz)] == want_inside:
                components.union(n, node_index(mx, my, mz))
    return components


def check_grid(values, stats):
    inside = [v > ISO for v in values]
    triangles = []
    for x in range(GRID - 1):
        for y in range(GRID - 1):
            for z in range(GRID - 1):
                config = 0
                for c in range(8):
                    if inside[node_index(x + CORNER_X[c], y + CORNER_Y[c], z + CORNER_Z[c])]:
                        config |= 1 << c
                for triangle in MC_TRI[config]:
                    triangles.append(tuple(vertex_id(x, y, z, e) for e in triangle))
                if x < GRID - 2:  # count the ambiguous faces shared with the next cube along x
                    flags = [is_inside(config, c) for c in FACES[1][2]]
                    if sum(flags) == 2 and flags[0] == flags[2]:
                        stats["shared ambiguous faces"] += 1

    # Closed and consistently oriented: every directed edge once, its reverse once.
    counts = directed_edges(triangles)
    for (a, b), count in counts.items():
        require(count == 1 and counts.get((b, a)) == 1, "grid surface is open or flips orientation at %s" % ((a, b),))

    surfaces = UnionFind()
    for a, b, c in triangles:
        surfaces.union(a, b)
        surfaces.union(a, c)
    inside_parts = node_components(inside, True, NEIGHBORS_6)
    outside_parts = node_components(inside, False, NEIGHBORS_18)
    exterior = outside_parts.find(0)  # node 0 is on the forced-outside boundary

    positions, sides = {}, {}
    for vertex in surfaces.parent:
        axis, n = vertex % 3, vertex // 3
        x, y, z = node_coords(n)
        step = [0, 0, 0]
        step[axis] = 1
        m = node_index(x + step[0], y + step[1], z + step[2])
        require(inside[n] != inside[m], "vertex on an edge that does not cross")
        t = Fraction(ISO - values[n], values[m] - values[n])
        position = [Fraction(x), Fraction(y), Fraction(z)]
        position[axis] += t
        positions[vertex] = position
        inner, outer = (n, m) if inside[n] else (m, n)
        sides[vertex] = (inside_parts.find(inner), outside_parts.find(outer))

    volumes, separated = {}, {}
    for a, b, c in triangles:
        root = surfaces.find(a)
        volumes[root] = volumes.get(root, 0) + dot(positions[a], cross(positions[b], positions[c]))
    for vertex, pair in sides.items():
        root = surfaces.find(vertex)
        require(separated.setdefault(root, pair) == pair,
                "a surface component touches two inside or two outside regions")
    require(len(set(separated.values())) == len(separated), "two surface components separate the same regions")
    for root, volume in volumes.items():
        # The free block is only 4 nodes wide, too small for fluid inside a cavity inside fluid, so a surface either
        # bounds fluid against the exterior (outward normals: positive volume) or walls in an enclosed outside cavity
        # (normals point into the cavity: negative volume, or zero when every outside node of the cavity sits exactly
        # at the iso level and the wall collapses onto them).
        cavity = separated[root][1] != exterior
        require(volume <= 0 if cavity else volume > 0,
                "surface component with signed volume %s (cavity: %s) is oriented wrongly" % (float(volume), cavity))
        stats["cavities"] += cavity
        stats["surfaces"] += 1
    stats["triangles"] += len(triangles)
    stats["grids"] += 1


def vertex_id(x, y, z, e):
    s = EDGE_START[e]
    return node_index(x + CORNER_X[s], y + CORNER_Y[s], z + CORNER_Z[s]) * 3 + EDGE_AXIS[e]


def random_grid(rng, pick):
    values = []
    for n in range(GRID ** 3):
        x, y, z = node_coords(n)
        boundary = min(x, y, z) == 0 or max(x, y, z) == GRID - 1
        values.append(0 if boundary else pick(rng))
    return values


def check_random_grids():
    stats = {"grids": 0, "triangles": 0, "surfaces": 0, "cavities": 0, "shared ambiguous faces": 0}
    rng = random.Random(20261117)
    families = [
        (500, lambda r: r.randint(0, 2 * ISO)),                               # uniform values in [0, 1]
        (150, lambda r: r.choice((0, ISO - 1, ISO, ISO, ISO + 1, 2 * ISO))),  # exactly at and just around iso
        (150, lambda r: r.randint(ISO + 1, 2 * ISO) if r.random() < 0.85 else r.randint(0, ISO)),  # cavities
    ]
    for count, pick in families:
        for _ in range(count):
            check_grid(random_grid(rng, pick), stats)
    return stats


# --- Java output ------------------------------------------------------------------------------------------------------

def java_int_list(values):
    return ", ".join(str(v) for v in values)


def java_source(edge_table, tri_table):
    out = []
    w = out.append
    w("// SPDX-License-Identifier: Apache-2.0")
    w("// Generated by tools/generate_marching_cubes_tables.py - do not edit by hand, change the script and run it again.")
    w("package dev.tore.fluidphysics.client.render;")
    w("")
    w("/**")
    w(" * Marching cubes lookup tables for the smooth fluid surface (Plan.md §8.2): for each of the 256 inside/outside")
    w(" * configurations of a grid cell's corners, which cell edges the iso-surface crosses and how the crossing points")
    w(" * are joined into triangles. Generated by {@code tools/generate_marching_cubes_tables.py}, which derives them from")
    w(" * first principles (clean-room) and checks them before writing; do not edit this file by hand.")
    w(" *")
    w(" * <p><b>Corners.</b> Corner {@code c} of a cell sits at offset {@code (c & 1, (c >> 1) & 1, (c >> 2) & 1)}, see")
    w(" * {@link #CORNER_X}, {@link #CORNER_Y} and {@link #CORNER_Z}. Bit {@code c} of a configuration index is set when")
    w(" * corner {@code c} is inside the fluid: its field value is strictly greater than the iso level. Values at or")
    w(" * below the iso level are outside.")
    w(" *")
    w(" * <p><b>Edges.</b> Edge {@code e} joins corner {@code EDGE_START[e]}, its lower-coordinate end, to corner")
    w(" * {@code EDGE_END[e] = EDGE_START[e] | (1 << EDGE_AXIS[e])}. Edges 0-3 run along x, 4-7 along y and 8-11 along")
    w(" * z; within each group the start corner's two other coordinates run (0,0), (1,0), (0,1), (1,1), the lower axis")
    w(" * first. Neighbouring cells number a shared edge differently, but its start node plus its axis name it")
    w(" * globally, which is how a mesher shares a vertex between the cells around an edge.")
    w(" *")
    w(" * <p><b>Triangles.</b> {@code TRI_TABLE[config]} lists edge indices, three per triangle, terminated by")
    w(" * {@code -1}: at most five triangles per configuration, so the last entry of every row is {@code -1}. The")
    w(" * vertices lie on the listed edges (linear interpolation of the field). Every triangle {@code (v0, v1, v2)} is")
    w(" * wound counter-clockwise when seen from outside the fluid (the side with values at or below the iso level):")
    w(" * {@code (v1 - v0) x (v2 - v0)} points from the inside to the outside.")
    w(" *")
    w(" * <p><b>Ambiguous faces.</b> When the two inside corners of a cell face sit on a diagonal, one fixed rule decides")
    w(" * how the face is cut, for every configuration: the inside corners stay separate, each is cut off by its own")
    w(" * segment. The rule only depends on the four corners of that face, so both cells sharing a face cut it the same")
    w(" * way and the surface is closed and crack-free, unlike the classic table built with complement symmetry")
    w(" * (Plan.md §8.4.4). In terms of digital topology the inside is 6-connected and the outside 18-connected.")
    w(" */")
    w("public final class MarchingCubesTables {")
    w("    /** X offset of each corner within its cell: {@code c & 1}. */")
    w("    public static final int[] CORNER_X = {%s};" % java_int_list(CORNER_X))
    w("    /** Y offset of each corner within its cell: {@code (c >> 1) & 1}. */")
    w("    public static final int[] CORNER_Y = {%s};" % java_int_list(CORNER_Y))
    w("    /** Z offset of each corner within its cell: {@code (c >> 2) & 1}. */")
    w("    public static final int[] CORNER_Z = {%s};" % java_int_list(CORNER_Z))
    w("")
    w("    /** Lower-coordinate corner of each edge. */")
    w("    public static final int[] EDGE_START = {%s};" % java_int_list(EDGE_START))
    w("    /** Upper corner of each edge: {@code EDGE_START[e] | (1 << EDGE_AXIS[e])}. */")
    w("    public static final int[] EDGE_END = {%s};" % java_int_list(EDGE_END))
    w("    /** Direction of each edge: 0 = x, 1 = y, 2 = z. */")
    w("    public static final int[] EDGE_AXIS = {%s};" % java_int_list(EDGE_AXIS))
    w("")
    w("    /** Per configuration, bit {@code e} is set when edge {@code e} crosses the iso-surface. */")
    w("    public static final int[] EDGE_TABLE = {")
    per_line = 8
    for first in range(0, 256, per_line):
        chunk = ", ".join("0x%03x" % m for m in edge_table[first:first + per_line])
        last = first + per_line >= 256
        w("        %s%s // %d-%d" % (chunk, "" if last else ",", first, first + per_line - 1))
    w("    };")
    w("")
    w("    /**")
    w("     * Per configuration, the triangles as edge indices, three per triangle, counter-clockwise seen from outside the")
    w("     * fluid, terminated by {@code -1}. The comment shows the configuration and its inside corners as bits 7..0.")
    w("     */")
    w("    public static final int[][] TRI_TABLE = {")
    for config in range(256):
        row = [e for triangle in tri_table[config] for e in triangle]
        row += [-1] * (ROW_LENGTH - len(row))
        entries = ", ".join("%2d" % v for v in row)
        comma = "" if config == 255 else ","
        w("        {%s}%s // %d: %s" % (entries, comma, config, format(config, "08b")))
    w("    };")
    w("")
    w("    private MarchingCubesTables() {")
    w("    }")
    w("}")
    return "\n".join(out) + "\n"


MC_TRI = None


def main():
    global MC_TRI
    try:
        edge_table, tri_table, worst_alignment = generate()
        MC_TRI = tri_table
        check_configs(edge_table, tri_table)
        pairs, ambiguous_pairs = check_shared_faces()
        stats = check_random_grids()
    except TableError as error:
        print("marching cubes table check FAILED, nothing written:", error, file=sys.stderr)
        sys.exit(1)

    counts = [len(t) for t in tri_table]
    print("configs: 256, triangles per config: max %d, total %d, ambiguous-face configs: %d"
          % (max(counts), sum(counts),
             sum(1 for c in range(256) if any(len(face_segments(c, ring)) == 2 for _, _, ring in FACES))))
    print("worst triangle alignment with its loop (cosine, at edge midpoints): %.3f" % worst_alignment)
    print("shared faces checked: %d cube pairs, %d of them on an ambiguous face" % (pairs, ambiguous_pairs))
    print("random grids checked: %d (%dx%dx%d nodes), %d triangles, %d closed surfaces (%d cavity walls), "
          "%d shared ambiguous faces" % (stats["grids"], GRID, GRID, GRID, stats["triangles"], stats["surfaces"],
                                          stats["cavities"], stats["shared ambiguous faces"]))

    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(java_source(edge_table, tri_table))
    print("wrote", os.path.relpath(OUTPUT, ROOT))


if __name__ == "__main__":
    main()
