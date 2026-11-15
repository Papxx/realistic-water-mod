# Implementation Plan & Technical Reference (`plan.md`)
**Project:** Realistic Fluid & Granular Physics (`fluidphysics`)
**Targets:** Minecraft `1.21.1` & `1.21.11` | Loaders: **Fabric** & **NeoForge** | Java 21 (Mojmap)

---

## 1. Repository & Build Layout (Stonecutter + MultiLoader)

Use **Stonecutter (`dev.kikugie.stonecutter` v0.5+)** so a single codebase compiles for all 4 targets (`1.21.1-fabric`, `1.21.1-neoforge`, `1.21.11-fabric`, `1.21.11-neoforge`).

### Directory Structure
```text
fluidphysics/
├── CLAUDE.md
├── plan.md
├── settings.gradle.kts
├── stonecutter.gradle.kts
├── gradle.properties
├── versions/
│   ├── 1.21.1/gradle.properties
│   └── 1.21.11/gradle.properties
├── common/
│   └── src/main/java/dev/tore/fluidphysics/
│       ├── FluidPhysicsCommon.java
│       ├── platform/IPlatformHelper.java
│       ├── registry/ModRegistry.java
│       ├── network/ (FluidSpawnPayload, FluidImpulsePayload, FluidCondensePayload)
│       ├── physics/ (ParticleSoA, SpatialHashGrid, ChunkVoxelCache, PbfSolver)
│       ├── interaction/ (EntityBuoyancyHandler, CauldronFillHandler, FluidReactionHandler)
│       ├── client/render/ParticleBatchRenderer.java
│       └── mixin/ (BoatBuoyancyMixin, FallingBlockMixin, ExplosionMixin)
├── fabric/
│   └── src/main/java/dev/tore/fluidphysics/fabric/
└── neoforge/
    └── src/main/java/dev/tore/fluidphysics/neoforge/
```

### Version Matrix (`versions/<ver>/gradle.properties`)
- **Shared:** `java_version=21`
- **`versions/1.21.1/gradle.properties`:**
  - `minecraft_version=1.21.1`
  - `fabric_loader_version=0.16.9`
  - `fabric_api_version=0.110.0+1.21.1`
  - `neoforge_version=21.1.80`
- **`versions/1.21.11/gradle.properties`:**
  - `minecraft_version=1.21.11`
  - `fabric_loader_version=0.16.9`
  - `fabric_api_version=0.115.0+1.21.11`
  - `neoforge_version=21.11.0-beta` (or latest `21.11.+` release)

---

## 2. Mandatory Version Cheat-Sheet (`1.21.1` vs `1.21.11`)

> **CRITICAL FOR CLAUDE:** Never use 1.20.x APIs. Always use the patterns below!

### A. ResourceLocation (Identical in 1.21.1 and 1.21.11)
NEVER use `new ResourceLocation(...)` (removed in 1.21). Always use:
```java
public static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
}
```

### B. Item & Block Registration (`1.21.1` vs `1.21.11`)
Starting in `1.21.2+` (including `1.21.11`), calling `new Item(properties)` or `new Block(properties)` without calling `.setId(ResourceKey)` on the properties object throws `NullPointerException: Item id not set`.
Use this exact helper in `ModRegistry.java`:
```java
public static Item.Properties itemProps(ResourceLocation id) {
    Item.Properties props = new Item.Properties();
    //? if >=1.21.2 {
    props.setId(ResourceKey.create(Registries.ITEM, id));
    //?}
    return props;
}

public static BlockBehaviour.Properties blockProps(ResourceLocation id, BlockBehaviour.Properties base) {
    //? if >=1.21.2 {
    base.setId(ResourceKey.create(Registries.BLOCK, id));
    //?}
    return base;
}
```

### C. Boat Class Hierarchy (`Boat` vs `AbstractBoat`)
In `1.21.1`, all boats inherit from `net.minecraft.world.entity.vehicle.Boat`.
In `1.21.11` (`>=1.21.2`), `Boat` was split into wood-specific classes inheriting from `net.minecraft.world.entity.vehicle.AbstractBoat`.
```java
//? if >=1.21.2 {
import net.minecraft.world.entity.vehicle.AbstractBoat;
//?} else {
import net.minecraft.world.entity.vehicle.Boat;
//?}
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
- Grid resolution: `CELL_SIZE = 0.25f` (`INV_CELL_SIZE = 4.0f`).
- Hash table size: `HASH_SIZE = 65536` (power of 2, mask `0xFFFF`).
- Hash function:
  ```java
  public static int hashCell(int cx, int cy, int cz) {
      return ((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & 0xFFFF;
  }
  ```
- Every substep:
  1. `Arrays.fill(cellCount, 0)`
  2. Count particles per hash bucket (`cellCount[hash]++`).
  3. Compute prefix sum into `cellStart[hash]`.
  4. Place particle indices into `sortedIndices[]`.
  5. Neighbor query iterates `dx, dy, dz` in `-1..1` (27 cells) using `cellStart` and `cellCount`.

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
- When a particle collides with a solid sub-voxel (`isSolid == true`), push it out along the minimum penetration axis (`nx, ny, nz`) and apply material-dependent restitution & friction.

### 3.4 `PbfSolver.java` (Position Based Fluids + Granular & Viscoelastic)
Run `SUBSTEPS = 2` per game tick (`dt = 0.025f` per substep):
- **Smoothing radius:** `h = 0.35f`, `h2 = h * h`, rest density `rho0 = 64.0f` (64 particles per $1\text{ m}^3$ block).
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

- [ ] **Step 1 (Scaffold):** Set up Stonecutter + MultiLoader (`common`, `fabric`, `neoforge`) for `1.21.1` and `1.21.11`. Implement `IPlatformHelper` and `ModRegistry` with the `.setId()` version check. Verify build passes for all 4 targets.
- [ ] **Step 2 (Physics Core & Unit Test):** Implement `ParticleSoA`, `SpatialHashGrid`, `ChunkVoxelCache` (64-bit `long` sub-voxels), and `PbfSolver`. Create `PbfSolverTest.java` in `common/src/test/java/` simulating 16,384 particles for 100 steps and assert execution time is under 3.5 ms per step with zero heap allocations.
- [ ] **Step 3 (Networking & Commands):** Implement `FluidSpawnPayload`, `FluidImpulsePayload`, `FluidCondensePayload`, and `/fluidphysics (stats|clear|spawn|limit)`.
- [ ] **Step 4 (Client Batch Renderer):** Implement `ParticleBatchRenderer` rendering instanced/batched micro-cubes (`0.22f` size) with velocity-based foam coloring for water, temperature glow for lava, translucent bouncy tint for slime, and grain shading for sand.
- [ ] **Step 5 (Items & World Interactions):** Add Physics Buckets, Crumbling Sand Block, Cauldron filling, Water+Lava Obsidian reaction, Condensation back to Vanilla blocks, and Boat/Anvil/TNT Mixins.
