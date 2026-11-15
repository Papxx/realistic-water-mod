# Project: Realistic Fluid & Granular Physics Mod (Multi-Loader & Multi-Version)

> **Source of truth:** `Plan.md` holds the detailed specs, version cheat-sheet and the step-by-step execution order. If this file and `Plan.md` disagree, `Plan.md` wins. Work through the steps in `Plan.md` §5 sequentially, run `./gradlew build` after each, and tick the checkbox when done.
> **Verify, don't guess:** Version numbers in `Plan.md` §1 (Fabric/NeoForge/Stonecutter) are unverified; check them against the real Maven repos before use.

## 1. Target Environment
- **Minecraft Versions:** `1.21.1` and `1.21.11` (managed via **Stonecutter** preprocessor comments `//? if >=1.21.2` where Mojang APIs differ).
- **Mod Loaders:** **Fabric** and **NeoForge** (MultiLoader architecture: `common`, `fabric`, `neoforge`).
- **Language & Mappings:** Java 21, Official Mojang Mappings (Mojmap) across all modules.
- **Zero External API Bloat:** Avoid heavy runtime dependencies where possible; use a clean ServiceLoader `IPlatformHelper` pattern in `common` for loader-specific hooks (Networking, Render Events, Registry).

## 2. Critical Version Differences (`1.21.1` vs `1.21.11`)
ALWAYS respect these API differences using Stonecutter conditions or helper methods:
1. **Block/Item Properties:**
   - In `1.21.11` (`>=1.21.2`), `Item.Properties` and `BlockBehaviour.Properties` MUST call `.setId(ResourceKey)` BEFORE passing them into `new Item(...)` or `new Block(...)`.
   - In `1.21.1`, `.setId(...)` does not exist. Always use the factory helpers `ModRegistry.itemProps(ResourceLocation id)` / `ModRegistry.blockProps(id, base)` (see `Plan.md` §2B).
2. **Boats (Buoyancy Mixin):**
   - In `1.21.1`, target `net.minecraft.world.entity.vehicle.Boat`.
   - In `1.21.11`, target `net.minecraft.world.entity.vehicle.boat.AbstractBoat`.
3. **Identifier / ResourceLocation:**
   - `1.21.11` renamed `ResourceLocation` to `Identifier`. Write `Identifier` in sources; the string replacement in `stonecutter.gradle.kts` turns it back into `ResourceLocation` for `1.21.1` (see `Plan.md` §2A).
4. **Rendering Hooks:**
   - Isolate vertex building and GPU instancing inside `client/render/ParticleBatchRenderer.java` so minor `BufferBuilder` / `RenderSystem` changes between `1.21.1` and `1.21.11` only touch one class.

## 3. Core Physics Architecture (Strict Performance Rules)
1. **NEVER use Minecraft Entities (`Entity`) or Objects per Particle:**
   - All particles live in a pre-allocated **Structure of Arrays (SoA)** inside `ParticleSoA` (`float[] posX/Y/Z, prevX/Y/Z, predX/Y/Z, velX/Y/Z, lambda; byte[] material, neighbors; short[] sleepTicks`; exact layout in `Plan.md` §3.1).
   - Hard cap: `MAX_PARTICLES = 32768` (array size). Configurable runtime limit `maxParticles` via `/fluidphysics limit` (default `32768` on client; server default is a lower value, `8192`, to be tuned).
   - **Zero Heap Allocations inside the tick loop** (no `new Vec3()`, no `BlockPos` boxing—use packed `long` via `BlockPos.asLong(x, y, z)`).
2. **Spatial Indexing (O(N) Counting-Sort Uniform Grid):**
   - Grid cell size = smoothing radius `h = 0.35f`, so the 27-cell neighbour search is exact. The 0.25 sub-voxel (4x4x4 per block) is the collision unit of `ChunkVoxelCache` (see `Plan.md` §3.2).
   - Use a flat 1D hash table with prefix-sum counting sort (`cellCount[]`, `cellStart[]`, `sortedParticleIndices[]`) rebuilt every sub-step without memory allocation.
3. **Unified PBD / PBF Solver (2–3 Substeps per Tick):**
   - **Water (`MATERIAL_WATER = 0`):** Position Based Fluids (PBF) density constraint, low viscosity (`0.02`), buoyancy on entities, splashes at high velocity.
   - **Lava (`MATERIAL_LAVA = 1`):** High viscosity (`0.45`), slower velocity damping, emits light via cached block-light positions, ignites entities. Water + Lava contact turns both particles into Obsidian/Cobblestone progress.
   - **Slime (`MATERIAL_SLIME = 2`):** Viscoelastic PBD: strong cohesion to neighbor slime particles + wall adhesion on stairs/blocks + elastic bounce impulse when players/anvils land on it.
   - **Sand (`MATERIAL_SAND = 3`):** Granular PBD/DEM: high static & dynamic friction, stacks at an angle of repose (~35°), zero tensile cohesion, rolls down stairs.
4. **World Collision Cache (`ChunkVoxelCache`):**
   - NEVER call `level.getBlockState()` per particle per substep.
   - Cache nearby chunks into a compact `4x4x4` sub-voxel bitmask (`long` per block) representing solid geometry (supporting stairs, slabs, cauldrons, trapdoors). Invalidate only on `BlockUpdate`.
5. **Strict Mass Conservation & Condensation (Anti-Lag):**
   - 1 Bucket / 1 Full Block = **64 particles** (4x4x4 sub-blocks). No infinite duplication!
   - **Condensation (Re-Blockification):** When 64 particles of the same material rest inside a single block space (`sleepTicks > 60`, i.e. particle resting below the sleep velocity threshold for 60 ticks; checked once per second), despawn those 64 particles and place the corresponding Vanilla block (or fill a Cauldron / form Obsidian).

## 4. Multiplayer & Networking (`CustomPacketPayload`)
- **NEVER send per-particle coordinates over the network!**
- Use an **Event + Coarse Grid Sync** architecture via `StreamCodec`:
  - `FluidSpawnPayload(long packedBlockPos, byte materialType, short count, float dirX, float dirY, float dirZ)`
  - `FluidImpulsePayload(float x, float y, float z, float radius, float force)` (for TNT, falling anvils, pistons, players jumping in)
  - `FluidCondensePayload(long packedBlockPos, byte materialType, short removedCount)`
  - `FluidClearPayload()` (for `/fluidphysics clear`)
- Server code changes particles only through `server/ServerSimulations`, which applies the change and sends the matching event.

## License & Clean-Room Policy
- The project is licensed under `Apache-2.0` (`Copyright 2026 Papxx`). `LICENSE` and `NOTICE` live in the repo root and are packed into the root of every built jar.
- **Strict clean-room rule:** Under no circumstances may source code, shader code or texture material from other mods (in particular `sskaffy/Realistic-Water`) be copied. All algorithms, shaders and assets must be implemented 100 % independently or generated procedurally.
- Every new Java source file starts with the short SPDX header:
  `// SPDX-License-Identifier: Apache-2.0`
