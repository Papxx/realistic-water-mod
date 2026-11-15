# Implementation Plan & Technical Reference (`Plan.md`)
**Project:** Realistic Fluid & Granular Physics (`fluidphysics`)
**Targets:** Minecraft `1.21.1` & `1.21.11` | Loaders: **Fabric** & **NeoForge** | Java 21 (Mojmap)

---

## 1. Repository & Build Layout (Stonecutter + MultiLoader)

Use **Stonecutter (`dev.kikugie.stonecutter` v0.5+)** so a single codebase compiles for all 4 targets (`1.21.1-fabric`, `1.21.1-neoforge`, `1.21.11-fabric`, `1.21.11-neoforge`).

**Verified toolchain (2026-11-15, Gradle runs on JDK 21):** Gradle `9.6.1` (wrapper), Stonecutter `0.9.8`, Fabric Loom `1.17.21` (plugin id `net.fabricmc.fabric-loom-remap`, used by `common` and `fabric`), ModDevGradle `2.0.148` (`neoforge`). Stonecutter `0.10+` and Loom `1.18+` only run on JDK 25, so stay on these lines until the build JDK is upgraded.

Stonecutter branches give one Gradle project per module and Minecraft version: `:common:<mc>`, `:fabric:<mc>`, `:neoforge:<mc>` (e.g. `./gradlew :fabric:1.21.1:build`). Sources live in `<module>/src`; `<module>/versions/<mc>/` only holds build output. `src/` is committed in the state of the active version `1.21.11`; code for `1.21.1` sits in Stonecutter comments.

### Directory Structure
```text
fluidphysics/
├── CLAUDE.md
├── Plan.md
├── settings.gradle.kts
├── stonecutter.gradle.kts
├── gradle.properties
├── versions/
│   ├── 1.21.1/gradle.properties
│   └── 1.21.11/gradle.properties
├── common/
│   ├── build.gradle.kts
│   └── src/main/java/dev/tore/fluidphysics/
│       ├── FluidPhysicsCommon.java
│       ├── platform/ (IPlatformHelper, Services)
│       ├── registry/ModRegistry.java
│       ├── network/ (FluidSpawnPayload, FluidImpulsePayload, FluidCondensePayload)
│       ├── physics/ (ParticleSoA, SpatialHashGrid, ChunkVoxelCache, PbfSolver)
│       ├── interaction/ (EntityBuoyancyHandler, CauldronFillHandler, FluidReactionHandler)
│       ├── client/render/ParticleBatchRenderer.java
│       └── mixin/ (BoatBuoyancyMixin, FallingBlockMixin, ExplosionMixin)
├── fabric/
│   ├── build.gradle.kts
│   └── src/main/java/dev/tore/fluidphysics/fabric/
└── neoforge/
    ├── build.gradle.kts
    └── src/main/java/dev/tore/fluidphysics/neoforge/
```

### Version Matrix (`versions/<ver>/gradle.properties`)
Checked against the Fabric and NeoForge Maven repos on 2026-11-15 (latest release of each line).
- **Shared:** `java_version=21` (root `gradle.properties`)
- **`versions/1.21.1/gradle.properties`:**
  - `minecraft_version=1.21.1`
  - `fabric_loader_version=0.20.1`
  - `fabric_api_version=0.116.18+1.21.1`
  - `neoforge_version=21.1.266`
- **`versions/1.21.11/gradle.properties`:**
  - `minecraft_version=1.21.11`
  - `fabric_loader_version=0.20.1`
  - `fabric_api_version=0.141.6+1.21.11`
  - `neoforge_version=21.11.48`

---

## 2. Mandatory Version Cheat-Sheet (`1.21.1` vs `1.21.11`)

> **CRITICAL FOR CLAUDE:** Never use 1.20.x APIs. Always use the patterns below!

### A. Identifier / ResourceLocation (renamed in `1.21.11`)
Mojang renamed `net.minecraft.resources.ResourceLocation` to `net.minecraft.resources.Identifier` in `1.21.11`, together with every name built on it (`readResourceLocation` -> `readIdentifier`, `ResourceLocationArgument` -> `IdentifierArgument`, ...).
Sources are written with the `1.21.11` name. `stonecutter.gradle.kts` holds one string replacement (`Identifier` -> `ResourceLocation`) that produces the `1.21.1` sources, so never branch on this by hand, and do not use the word `Identifier` in names of our own.
Not covered by the replacement: `ResourceKey.location()` (`1.21.1`) became `ResourceKey.identifier()` (`1.21.11`).
NEVER use `new ResourceLocation(...)` / `new Identifier(...)` (constructor is private since 1.21). Always use:
```java
public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
}
```

### B. Item & Block Registration (`1.21.1` vs `1.21.11`)
Starting in `1.21.2+` (including `1.21.11`), calling `new Item(properties)` or `new Block(properties)` without calling `.setId(ResourceKey)` on the properties object throws `NullPointerException: Item id not set`.
Use this exact helper in `ModRegistry.java`:
```java
public static Item.Properties itemProps(Identifier id) {
    Item.Properties props = new Item.Properties();
    //? if >=1.21.2 {
    props.setId(ResourceKey.create(Registries.ITEM, id));
    //?}
    return props;
}

public static BlockBehaviour.Properties blockProps(Identifier id, BlockBehaviour.Properties base) {
    //? if >=1.21.2 {
    base.setId(ResourceKey.create(Registries.BLOCK, id));
    //?}
    return base;
}
```

### C. Boat Class Hierarchy (`Boat` vs `AbstractBoat`)
In `1.21.1`, all boats inherit from `net.minecraft.world.entity.vehicle.Boat`.
In `1.21.11`, `Boat` is split into wood-specific classes inheriting from `net.minecraft.world.entity.vehicle.boat.AbstractBoat` (note the `boat` subpackage).
```java
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
//?} else {
/*import net.minecraft.world.entity.vehicle.Boat;
*///?}
```
In Mixins or buoyancy checks, wrap the target class with Stonecutter comments so both versions compile cleanly.

### D. VertexConsumer / BufferBuilder API (1.21+ Syntax)
**WARNING:** `.vertex(...).color(...).endVertex()` DOES NOT EXIST in 1.21!
Always use the 1.21 `VertexConsumer` chain (note: NO `.endVertex()` call at the end!):
```java
vc.addVertex(poseMatrix, x, y, z)
  .setColor(r, g, b, a)
  .setUv(u, v)
  .setLight(packedLight)
  .setNormal(poseEntry, nx, ny, nz);
```

### E. CustomPacketPayload & StreamCodec (Identical in 1.21.1 & 1.21.11)
Define all network packets in `common/network/` using records:
```java
public record FluidSpawnPayload(
    long packedBlockPos,
    byte materialType,
    short count,
    float vx, float vy, float vz
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FluidSpawnPayload> TYPE =
        new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_spawn"));

    public static final StreamCodec<ByteBuf, FluidSpawnPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, FluidSpawnPayload::packedBlockPos,
        ByteBufCodecs.BYTE, FluidSpawnPayload::materialType,
        ByteBufCodecs.SHORT, FluidSpawnPayload::count,
        ByteBufCodecs.FLOAT, FluidSpawnPayload::vx,
        ByteBufCodecs.FLOAT, FluidSpawnPayload::vy,
        ByteBufCodecs.FLOAT, FluidSpawnPayload::vz,
        FluidSpawnPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

---

## 3. Core Physics Engine Specification (Zero-GC in `common/physics/`)

### 3.1 `ParticleSoA.java` (Structure of Arrays)
- Pre-allocate primitive arrays of size `MAX_PARTICLES = 32768`:
  ```java
  public final float[] posX = new float[MAX_PARTICLES];
  public final float[] posY = new float[MAX_PARTICLES];
  public final float[] posZ = new float[MAX_PARTICLES];
  public final float[] prevX = new float[MAX_PARTICLES];
  public final float[] prevY = new float[MAX_PARTICLES];
  public final float[] prevZ = new float[MAX_PARTICLES];
  public final float[] predX = new float[MAX_PARTICLES];
  public final float[] predY = new float[MAX_PARTICLES];
  public final float[] predZ = new float[MAX_PARTICLES];
  public final float[] velX = new float[MAX_PARTICLES];
  public final float[] velY = new float[MAX_PARTICLES];
  public final float[] velZ = new float[MAX_PARTICLES];
  public final float[] lambda = new float[MAX_PARTICLES];
  public final byte[] material = new byte[MAX_PARTICLES]; // 0=WATER, 1=LAVA, 2=SLIME, 3=SAND
  public final short[] sleepTicks = new short[MAX_PARTICLES];
  public int activeCount = 0;
  ```
- **Removal (Swap-and-Pop in O(1)):** To despawn particle at index `i`, copy data from index `activeCount - 1` into `i` and decrement `activeCount`.

### 3.2 `SpatialHashGrid.java` (Zero-Allocation Counting Sort)
- Grid resolution: `CELL_SIZE = 0.35f`, equal to the smoothing radius `h` (§3.4). *Changed in Step 2:* with the original 0.25-block cells, a 27-cell search misses neighbours within `h = 0.35` that sit two cells away. The 0.25-block sub-voxels remain the unit of world collision (§3.3).
- Table size: `HASH_SIZE = 65536` buckets (16 bits).
- Bucket of a cell (*changed in Step 2*): its linear index inside the bounding box of all particles (x fastest, then z, then y), each axis wrapped to a power of two so the box fits into 16 bits; a larger box wraps and its far-apart cells share buckets. The multiplicative hash `((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & 0xFFFF` scattered the 27 neighbour cells across the whole particle array. The linear index keeps them adjacent in memory, which made the neighbour search about 2× faster. Within one 3×3×3 neighbourhood all buckets are distinct, so no particle is found twice.
- Every substep:
  1. Compute each particle's cell and the bounding box (parallel).
  2. `Arrays.fill(cellCount, 0)`, count particles per bucket (`cellCount[bucket]++`).
  3. Compute prefix sum into `cellStart[bucket]`.
  4. Place particle indices into `sortedIndices[]`.
  5. Copy the particles into grid order and build one neighbour list per particle (at most 32 neighbours). A neighbourhood is 9 contiguous ranges, because the three cells of an x-row have consecutive buckets.

### 3.3 `ChunkVoxelCache.java` (The 64-Bit `long` Sub-Voxel Trick)
- A single Minecraft block (`1.0 x 1.0 x 1.0`) divided into `0.25` cells has exactly `4 x 4 x 4 = 64` sub-voxels.
- Represent every block's solid geometry as a **single `long` (64 bits)** where bit index is:
  ```java
  public static int subVoxelBit(int sx, int sy, int sz) { // sx, sy, sz in 0..3
      return (sy << 4) | (sz << 2) | sx; // 0..63
  }
  public static boolean isSolid(long blockMask, int sx, int sy, int sz) {
      return (blockMask & (1L << subVoxelBit(sx, sy, sz))) != 0L;
  }
  ```
- **How to build `blockMask` from a `BlockState`:**
  - Air / Fluid: `0x0000000000000000L`
  - Full Cube (`isCollisionShapeFullBlock`): `0xFFFFFFFFFFFFFFFFL` (`-1L`)
  - Stairs, Slabs, Cauldrons, Trapdoors: Sample `VoxelShape` boxes once when caching the block and set the corresponding bits in the `long`.
- Store cached `long` masks in a fast open-addressed `Long2LongOpenHashMap` (from `it.unimi.dsi.fastutil.longs`, already bundled with Minecraft!) mapping `BlockPos.asLong(bx, by, bz)` -> `subVoxelMask`.
- When a particle collides with a solid sub-voxel (`isSolid == true`), push it out through the face it entered by (swept from its position at the start of the substep; falls back to the nearest open face), then apply position-based Coulomb friction (static below `friction × penetration`, kinetic above it) and, for impacts faster than 1 block/s, restitution.
- *Implemented in Step 2:* a sub-voxel is solid if any collision box overlaps it, so thin walls (cauldron sides, trapdoors, carpets) never leak particles. Masks are memoised per `BlockState` (unless the shape depends on the position), whole chunk sections are cached at once from the main thread (`ensureCached`, `getChunkNow` only, never loads chunks), and a `version()` counter tells solvers when to drop remembered masks.

### 3.4 `PbfSolver.java` (Position Based Fluids + Granular & Viscoelastic)
Run `SUBSTEPS = 2` per game tick (`dt = 0.025f` per substep), `SOLVER_ITERATIONS = 2` per substep:
- **Smoothing radius:** `h = 0.35f`, `h2 = h * h`. Mass 1 per particle, 64 particles per block.
- **Rest density** *(calibrated in Step 2)*: `rho0 = 52.0f`, not 64. With only about 10 neighbours inside `h`, the kernel sum depends on the arrangement (spawn lattice ≈ 62, irregular liquid packing more), and 52 keeps a resting one-block pool at about its volume.
- **Stabilisation** *(added in Step 2; a plain 2-iteration Jacobi PBF compressed pools by up to 45 %, kept ringing and let sand piles creep flat)*:
  - Solid sub-voxel faces add density like a layer of resting particles one spacing away, so fluid at walls and floors is not under-dense.
  - Fluid position corrections are under-relaxed by 0.5 and mass-scaled by height (the upper particle of a pair takes up to 50 % more of the correction; Macklin et al. 2014). Result: pools 1, 3 and 6 blocks deep keep 94–96 % of their volume and come to rest completely within 20–25 s.
  - Sand uses particle contacts (diameter 0.25) with Coulomb friction instead of the density constraint. Its corrections are shared by height too (the upper grain takes all of it) and are never over-relaxed beyond the overlap. A grain holds on a 30° contact and slides at 45° (`atan(0.75)` = 36.9°), and poured sand forms a pile.
  - Speed limit `0.95 × 0.25 / dt` = 9.5 blocks/s, so no particle crosses a whole sub-voxel per substep (no tunnelling through trapdoors).
- **Threading** *(moved forward from §6.1.5)*: every pass is Jacobi-style (each particle only writes its own data) and is split across a fixed `WorkerPool` (default: half the logical CPUs, at most 8). The calling thread takes part, and dispatching allocates nothing.
- **Precomputed Kernel Constants:**
  - `POLY6_COEFF = 315.0f / (64.0f * (float) Math.PI * (float) Math.pow(h, 9))`
  - `SPIKY_GRAD_COEFF = -45.0f / ((float) Math.PI * (float) Math.pow(h, 6))`
- **Material Behavior Matrix:**

| Material | ID | Gravity | Viscosity (XSPH) | Cohesion / Tensile | Friction (Wall/Floor) | Bounce (Restitution) | Special Rule |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Water** | `0` | `-9.81` | `0.02` | `0.01` | `0.02` | `0.10` | Extinguishes fire, pushes entities, fills Water Cauldron |
| **Lava** | `1` | `-9.81` | `0.45` | `0.08` | `0.25` | `0.00` | Ignites entities, +Water -> Obsidian/Cobble |
| **Slime** | `2` | `-9.81` | `0.35` | `0.30` ( Elastic ) | `0.60` (Sticky on walls) | `0.85` | Bounces falling entities & anvils upward |
| **Sand** | `3` | `-12.0` | `0.15` | `0.00` (Repulsive only) | `0.75` (Angle of repose) | `0.05` | Crumbles from `PhysicsSandBlock`, rolls down stairs |

---

## 4. Gameplay Systems: Mass Conservation, Cauldrons & Boats

1. **64-Particle Mass Rule (No Infinite Floods):**
   - Spawning from a bucket (`PhysicsWaterBucket`, `PhysicsLavaBucket`, `PhysicsSlimeBucket`) or breaking a `PhysicsSandBlock` spawns a $4 \times 4 \times 4$ micro-grid of **64 particles** inside that block space.
   - Using an empty bucket on active particles removes up to 64 particles of the same material within a 2.5-block raycast and returns the filled bucket.
2. **Condensation (Auto-Blockification to Save FPS):**
   - Once per second (20 ticks), scan block coordinates that contain $\ge 64$ sleeping particles (`sleepTicks > 60`) of the same material:
     - Despawn 64 particles and place the Vanilla block (`Blocks.WATER`, `Blocks.LAVA`, `Blocks.SLIME_BLOCK`, `Blocks.SAND`).
3. **Cauldron Filling (`Blocks.CAULDRON`):**
   - Because `Blocks.CAULDRON` has hollow inner sub-voxels in `ChunkVoxelCache`, particles physically drop inside the cauldron bowl!
   - When 21 particles of Water or Lava rest inside a Cauldron's inner bits, increment the Vanilla Cauldron level (`LayeredCauldronBlock.LEVEL` 1 -> 2 -> 3) and despawn those particles.
4. **4-Point Boat Buoyancy (`BoatBuoyancyMixin`):**
   - Instead of Vanilla's binary underwater check, sample particle count and average velocity at 4 corners of the boat's bounding box (`front-left`, `front-right`, `back-left`, `back-right`).
   - Apply upward buoyancy force proportional to submerged corner depth and tilt the boat's visual pitch/roll based on the height difference between front/back and left/right corners.
5. **Anvil & TNT Displacement:**
   - `FallingBlockEntity` (Anvil, Pointed Dripstone): Every tick it intersects fluid cells, apply downward & radial outward velocity impulse proportional to fall speed (`fallDistance`). If landing on `SLIME` particles, invert the anvil's `deltaMovement.y * -0.7`.
   - `Explosion`: Hook explosion events and apply a strong radial outward impulse (`FluidImpulsePayload`) to all particles within `radius * 2.0`.

---

## 5. Implementation Execution Order (For Claude)

Execute these steps sequentially and run `./gradlew build` after each step:

- [x] **Step 1 (Scaffold):** Set up Stonecutter + MultiLoader (`common`, `fabric`, `neoforge`) for `1.21.1` and `1.21.11`. Implement `IPlatformHelper` and `ModRegistry` with the `.setId()` version check. Verify build passes for all 4 targets.
- [x] **Step 2 (Physics Core & Unit Test):** Implement `ParticleSoA`, `SpatialHashGrid`, `ChunkVoxelCache` (64-bit `long` sub-voxels), and `PbfSolver`. Create `PbfSolverTest.java` in `common/src/test/java/` simulating 16,384 particles for 100 steps and assert execution time is under 3.5 ms per step (median after JIT warm-up, generous tolerance for CI noise) and verify zero heap allocations (e.g. via `ThreadMXBean.getThreadAllocatedBytes` delta around the step loop).
  - **Done (2026-11-15)** with one accepted deviation: the 3.5 ms target is not met yet. On a 6-core/12-thread desktop the median is about 5 ms per tick with 6 threads (about 19 ms on one thread). The test reports the target and enforces 8 ms as a regression guard; reaching 3.5 ms moved to Step 6b. Zero allocations are verified on the calling thread and on all worker threads.
- [ ] **Step 3 (Networking & Commands):** Implement `FluidSpawnPayload`, `FluidImpulsePayload`, `FluidCondensePayload`, and `/fluidphysics (stats|clear|spawn|limit)`.
- [ ] **Step 4 (Client Batch Renderer):** Implement `ParticleBatchRenderer` rendering instanced/batched micro-cubes (`0.22f` size) with velocity-based foam coloring for water, temperature glow for lava, translucent bouncy tint for slime, and grain shading for sand.
- [ ] **Step 5 (Items & World Interactions):** Add Physics Buckets, Crumbling Sand Block, Cauldron filling, Water+Lava Obsidian reaction, Condensation back to Vanilla blocks, and Boat/Anvil/TNT Mixins.

---

## 6. Performance & RAM Optimization Roadmap (Server + Client)

> Apply **after** Step 5 works correctly. Measure first (`/fluidphysics stats`: step ms, active particles, grid rebuild ms, bytes/tick allocated), optimize second. Every optimization needs a before/after number in `PbfSolverTest` or a JMH benchmark.

### 6.1 Shared (common/physics) – CPU
1. **Z-order (Morton) particle sorting:** Every ~8–16 ticks, reorder the SoA arrays by cell Morton code (reuse the counting-sort result). Neighbor particles end up adjacent in memory, so the cache-miss rate drops sharply. Ref: Ihmsen et al., *"A Parallel SPH Implementation on Multi-Core CPUs"* (Freiburg, compact hashing + Z-index sort), https://cg.informatik.uni-freiburg.de/publications/2011_CGF_dataStructuresSPH.pdf
2. **Compact / sparse grid:** Instead of a fixed 65536 hash table, only iterate non-empty cells (compact list of occupied cells). Same source as above.
3. **Neighbor list caching:** Build the neighbor list once per substep (`int[] neighborStart, int[] neighborIdx`, pre-allocated, capped at e.g. 48 neighbors per particle) and reuse it for all PBF solver iterations instead of re-walking 27 cells each iteration.
4. **SIMD (Java Vector API, `jdk.incubator.vector`) – optional, behind a feature flag:** SoA layout is already SIMD-friendly. Vectorize integration, XSPH viscosity, and lambda/delta-position loops. Needs `--add-modules jdk.incubator.vector`, so provide a scalar fallback (ServiceLoader). Reference experience: single-thread SIMD beat naive multi-threading for PBF (https://github.com/yoharol/High_Performance_PBD_Fluids). Only parallelize loops where each iteration is heavy.
5. **Parallelism strategy:** Use one dedicated physics thread (not the main tick thread) with double-buffered positions: the physics thread writes buffer A while the render/tick thread reads buffer B; swap per tick. Parallelize only the heavy parts (neighbor search, density solve) with a small fixed `ForkJoinPool`/chunked ranges; no per-tick lambda allocations.
6. **Adaptive substeps / adaptive resolution:** Fewer substeps (1 instead of 2–3) and a coarser solver for distant clusters. Ref: *Adaptive Position-Based Fluids* (https://arxiv.org/pdf/1608.04721).
7. **Sleeping islands:** Particles with `sleepTicks > N` are excluded from the solver and only kept in the grid as static neighbors. Wake them via `FluidImpulse` or block updates in a radius. Fully sleeping clusters are cheaply condensed into vanilla blocks (already in §4.2).
8. **Spatial budgets:** Hard per-tick time budget (e.g. 3.5 ms server / 2 ms client). If exceeded, reduce substeps, then solver iterations, then force earlier condensation, never drop ticks. Only simulate chunks within `simulationDistance` of a player; freeze the rest.
9. **Float-only math, no `Math.pow`/`sqrt` in hot loops:** Precompute kernel tables/constants; use fast inverse-sqrt-free formulations (compare squared distances first).

### 6.2 Shared – RAM
1. **Allocation size is fixed and small:** All SoA arrays at `MAX_PARTICLES = 32768` ≈ 15 float arrays × 128 KB + small arrays ≈ **2 MB**. Allocate lazily (only when the first particle spawns) and free after N idle minutes. Server uses the lower `maxParticles` (8192) → allocate arrays by that limit, not by the max.
2. **Drop redundant arrays:** `prev*` and `pred*` can share one scratch buffer pair when XPBD does not need both; `lambda` can be recomputed per iteration. Evaluate with a memory/accuracy test.
3. **Quantization for sleeping/network/render data:** Store render snapshots as `short` (fixed-point 1/1024 block relative to chunk origin) → 6 bytes/particle instead of 12.
4. **`ChunkVoxelCache` memory cap:** LRU eviction with a hard cap (e.g. 4096 blocks → `Long2LongOpenHashMap` ≈ 100 KB); only cache blocks that are **non-air and adjacent to a non-solid cell** that particles can reach. Full-cube/air are derived from a 2-bit-per-block "type" table instead of a `long`. Deduplicate identical `long` shape masks per `BlockState` (cache per state, not per position).
5. **No object churn:** Pool/reuse command and event objects; reuse payload builders; never allocate in `tick`, `render`, or networking send paths (verify with `ThreadMXBean.getThreadAllocatedBytes`, see Step 2).
6. **Use fastutil primitive collections only** (`Long2LongOpenHashMap`, `LongArrayList`), never `HashMap<Long, …>`.

### 6.3 Server-side
1. **Server particle cap & tick budget:** `maxParticles` default 8192 (see CLAUDE.md), configurable per dimension; spawn requests beyond the cap trigger forced condensation of the oldest sleeping groups first.
2. **Authoritative low-res sim:** The server may simulate with a coarser step (`SUBSTEPS = 1`, fewer iterations) because clients simulate the visuals; the server only needs positions of particles that influence gameplay (entity buoyancy, condensation, cauldrons, obsidian reaction). Clients run a deterministic-enough local sim driven by Spawn/Impulse/Condense events.
3. **Event + coarse-grid sync only:** Batch all payloads of a tick into one packet per player; send only to players within view distance (`PacketDistributor` / tracking-chunk based). Rate-limit impulses (merge impulses in the same tick and 2-block cell). Periodic resync: one coarse-grid hash (particle counts per block) every few seconds, not particle positions.
4. **Off-thread condensation/scans:** The once-per-second condensation scan runs on the physics thread over the grid (counts per block); only the final `level.setBlock` happens on the main thread via a pre-allocated queue.
5. **Chunk unload handling:** Freeze and serialize particles of unloaded chunks as a compact count-per-block record, or condense them into vanilla blocks immediately. Never keep simulating unloaded areas.
6. **Entity interaction sampling:** Boats/entities query the **grid** (cell counts) with O(1) lookups at 4 points, never iterate all particles.

### 6.4 Client-side
1. **Instanced rendering:** One draw call for all particles per material using instanced micro-cubes (per-instance data: position `3×short`/`float`, packed color/material `int`). Isolated in `ParticleBatchRenderer` (see CLAUDE.md §2.4). Fallback path for 1.21.1 / without instancing: a single persistent, pre-allocated `ByteBuffer` + one `BufferBuilder`/`MeshData` upload per frame. Similar goals to the *ImmediatelyFast* mod (batching draw calls for particles/entities), which is a good reference for how to hook the render pipeline without breaking Sodium compatibility.
2. **Persistent mapped / reused GPU buffers:** Allocate the instance VBO once at max size, update with `glBufferSubData`/orphaning (double/triple buffering). No per-frame buffer creation.
3. **Culling & LOD:** Frustum-cull by cell/cluster; fully interior particles (all 6 neighbors occupied) are not rendered; far clusters collapse to a lower-detail representation (larger merged quads, or the vanilla fluid/block model once condensed). Distance-based skipping of small foam details.
4. **Render interpolation, not simulation upsampling:** Simulate at 20 TPS (+substeps); interpolate positions with `partialTick` between `prev` and current instead of running more steps. Store render snapshots in a separate pre-allocated buffer (double buffered).
5. **Sodium/Embeddium/Iris compatibility:** Render in `AfterTranslucentParticles` / the loader render event only; do not touch chunk meshing. Avoid depth/blend state leaks (restore state after drawing).
6. **Client sim budget:** The client has its own adaptive budget; if `frameTime` rises above a threshold, reduce render distance of particles and substeps before dropping to a lower `maxParticles` (client option in config, default 32768).
7. **Shader-free "cheap mode":** An option to render sprites/billboards (4 vertices) or even points instead of cubes (12 triangles) → ~3–6× less vertex work for low-end GPUs.

### 6.5 Measurement & Regression Guards
- JMH benchmark module (`common/src/jmh`) for: grid rebuild, neighbor build, density solve, collision. Track ms/step at 4k / 16k / 32k particles.
- `/fluidphysics stats` shows: step ms (avg/p99), grid ms, neighbors/particle, active/sleeping count, voxel-cache size, bytes allocated per tick, last packet size.
- CI test gates: <3.5 ms/step @16k particles (Step 2 target; currently enforced at 8 ms, see the Step 2 status), zero allocations per step (calling thread and workers), voxel cache ≤ configured cap.
- Use Spark (profiler) on server and client for real-world validation; record results in `docs/perf/`.

### 6.6 Suggested Order
- [ ] **Step 6a:** Stats/profiling hooks + JMH baseline.
- [ ] **Step 6b:** Neighbor list caching, Morton re-sorting, sleeping islands, time budget. Must bring `PbfSolverTest` to the 3.5 ms Step 2 target (about 5 ms after Step 2), then lower its enforced limit from 8 ms to 3.5 ms. Spatially ordered buckets and per-substep neighbour lists already exist (§3.2); the open levers are sleeping islands (§6.1.7) and reusing neighbour lists across substeps.
- [ ] **Step 6c:** Instanced rendering, GPU buffer reuse, culling/LOD, interpolation.
- [ ] **Step 6d:** Network batching/rate limits, chunk-unload handling, voxel-cache LRU.
- [ ] **Step 6e (optional):** Vector API path + physics thread with double buffering.


---

## 7. Critical Edge Cases, 1.21 API Traps & Polish (Addendum)

### 7.1 The 1.21 `DeltaTracker` & 144+ FPS Interpolation (No 20-TPS Stutter!)
- **API Trap:** In Minecraft 1.21+, render events do NOT pass `float partialTick` directly anymore; they pass `DeltaTracker`. Always extract partial ticks via:
  ```java
  float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
  ```
- **Smooth Rendering:** Physics runs at 20 TPS, but rendering runs at 60–240+ FPS. NEVER render raw `posX[i], posY[i], posZ[i]`. Always interpolate in `ParticleBatchRenderer`:
  ```java
  float rx = Mth.lerp(partialTick, soa.prevX[i], soa.posX[i]) - camX;
  float ry = Mth.lerp(partialTick, soa.prevY[i], soa.posY[i]) - camY;
  float rz = Mth.lerp(partialTick, soa.prevZ[i], soa.posZ[i]) - camZ;
  ```
  Remember to copy `posX -> prevX` at the **very start** of every game tick (before the 2 substeps run).

### 7.2 Shader & RenderType Split (`1.21.1` vs `1.21.11`)
- **API Trap:** In `1.21.1`, shaders are bound via `RenderSystem.setShader(GameRenderer::getPositionColorShader)`. In `1.21.2+` up to `1.21.11`, Mojang moved core shaders to `CoreShaders.POSITION_COLOR` (and changed `RenderType` internals).
- **Safe Solution (Sodium & Iris Compatible):**
  - Prefer rendering through `MultiBufferSource` using standard `RenderType.translucent()` (for Water/Slime) and `RenderType.solid()` (for Lava/Sand), passing a 1x1 white/tinted atlas sprite UV (`TextureAtlasSprite`) and valid lightmap coordinates (`LevelRenderer.getLightColor(level, blockPos)` for Water/Slime/Sand, and `LightTexture.FULL_BRIGHT` (`0xF000F0`) for Lava).
  - If using direct shader binding, wrap it in Stonecutter:
    ```java
    //? if >=1.21.2 {
    RenderSystem.setShader( net.minecraft.client.renderer.CoreShaders.POSITION_COLOR );
    //?} else {
    RenderSystem.setShader( net.minecraft.client.renderer.GameRenderer::getPositionColorShader );
    //?}
    ```

### 7.3 Block-Update Wake-Up & Floodgates (The "Shower / Dam Break" Experiment)
- When a player breaks a glass block, opens a trapdoor, or retracts a piston holding back water/sand:
  1. Immediately invalidate that `BlockPos` in `ChunkVoxelCache.invalidate(packedBlockPos)`.
  2. **Wake up adjacent sleeping particles:** Query `SpatialHashGrid` in a $3 \times 3 \times 3$ block radius around the changed `BlockPos` and reset `soa.sleepTicks[i] = 0` for all particles found. Otherwise, sleeping particles will float in mid-air after the floor/wall is removed!
  3. **Vanilla Fluid Conversion Hook (Optional Config):** When a Vanilla Water/Lava block updates next to an opened floodgate, optionally convert that Vanilla block into 64 physics particles so dams burst naturally.

### 7.4 Surface Tension & Slime Stringiness (Visual Polish)
- **Water Foam & Droplets:** If a water particle has fewer than 6 neighbors (`isolated droplet`) or speed $> 0.45\text{f}$, blend its color toward white (`#E8F7FF`) and slightly shrink its render scale (`0.16f`) so splashes look like real spray droplets.
- **Slime Stretching (Non-Cube Look):** Stretch each rendered particle box slightly along its velocity vector (`velX, velY, velZ`) clamped to `1.8x` length while narrowing width by `1.0 / sqrt(stretch)` to preserve volume. This makes slime sliding down walls and water shooting out of pipes look organic instead of blocky.

### 7.5 Chunk Unload & World Save Guard (Anti-Memory-Leak)
- When a chunk unloads or the server stops (`ServerChunkEvents.CHUNK_UNLOAD` / `LevelEvent.Unload`):
  - Immediately run **forced condensation** on all particles inside that chunk (group every 64 particles into the nearest free `BlockPos` as a Vanilla fluid/sand/slime block and remove the rest). This prevents orphaned particles in unloaded chunks and preserves mass across restarts.

### 7.6 Multi-Core Scaling (Java 21 Parallel Solver)
- When `soa.activeCount > 4096`, execute the PBF density (`lambda[i]`) and position correction (`deltaPos`) loops in parallel chunks (e.g., batches of 512 particles via `ForkJoinPool.commonPool()`) while keeping array writes strictly indexed by particle `i` (no race conditions!).
- *Implemented differently in Step 2:* all solver passes run on the solver's own `WorkerPool` (one contiguous share per thread, from 2048 particles up), not on `ForkJoinPool.commonPool()`. The common pool is shared with the game and other mods, and submitting tasks to it allocates. Writes stay strictly indexed by particle.
