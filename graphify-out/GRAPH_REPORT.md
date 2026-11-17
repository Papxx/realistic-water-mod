# Graph Report - realistic-water-mod  (2026-11-17)

## Corpus Check
- 144 files · ~113,813 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1577 nodes · 4573 edges · 83 communities (55 shown, 28 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 778 edges (avg confidence: 0.8)
- Token cost: 28,000 input · 7,000 output

## Community Hubs (Navigation)
- FluidDensityField / .addToBin()
- arraylist / assertequals
- BlockEnvironment / .packedLight()
- StatsReport.java / StatsReport
- BoatBuoyancy / .apply()
- Exception / fractions
- SmoothFluidRenderer.java / GlossyLook
- blockhitresult / clipcontext
- .removeOutsideLoadedChunks() / .markRestInCluster()
- at / .swim()
- .clear() / .activeCount()
- com.mojang.serialization.MapCodec / PhysicsLightBlock.java
- PbfSolver / .applyCorrectionsAndCollide()
- IPlatformHelper.java / IPlatformHelper
- .pack() / ChunkVoxelCache.java
- blockbehaviour / blockitem
- SolverStepBenchmark.java / SolverStepBenchmark
- Floodgates / .dequeue()
- abstractboat / blockpos
- bytebufcodecs / FluidPhysicsCommon.java
- CauldronFilling / .accepts()
- ForcedCondensation / .collectCandidates()
- .networkLine() / Type
- MarchingCubesTablesTest / .checkField()
- buildcreativemodetabcontentsevent / clienttickevent
- .add() / .jitter()
- blockposargument / chunkpos
- atomicinteger / .workerThreads()
- PhysicsSandBlock.java / Override
- ClientSimulation.java / ClientSimulation
- com.mojang.blaze3d.vertex.PoseStack / BoatTilt.java
- .x() / .z()
- .forceEmpty() / .getMask()
- .removeMarked() / FunctionalInterface
- arrays / cell_size
- .renderLines() / ParticleBatchRenderer
- .sendToLevel() / .sendToTracking()
- .buildNeighbors() / .computeCells()
- BlockCache / .begin()
- ClientConfig.java / Services.java
- .clear() / .clear()
- os / pil
- abstractcauldronblock / blocks
- ClientConfig / .ClientConfig()
- FluidPhysicsCommand / .clear()
- Grid Fixed Cost (65536 buckets) / Solver Phase Timing Breakdown (neighbours 33%, density 41%)
- clientcommandregistrationcallback / clientplaynetworking
- Placer / .place()
- biconsumer / com.mojang.brigadier.CommandDispatcher
- .clear() / FluidConfig
- FluidSpawnPayload / .type()
- ChunkKeys.java / ChunkKeys
- .ofBlock() / MutableBlockPos
- builtinregistries / commandregistrationcallback
- com.mojang.blaze3d.vertex.BufferBuilder / .beginBuffer()
- FluidReactions / .cell()
- EntityContactTest / .aBoxFarFromAllParticlesTouchesNothing()
- Floodgates / FluidClearPayload
- biomecolors / camera
- .advance() / .applyWakes()
- FluidSyncPayload / .read()
- ChunkVoxelCache / Render interpolation with DeltaTracker partial tick
- FluidReactionsTest / .particlesReactWithVanillaFluids()
- Step 6a Solver Performance Baseline / Plan.md
- Boat class hierarchy (Boat vs AbstractBoat) / BoatMixin (buoyancy)
- FluidReactions (lava and water reaction) / Lava (material 1)
- CubeFilter / .test()
- Condensation (re-blockification) / ForcedCondensation
- gradlew / gradlew script
- MarchingCubesTables.java / MarchingCubesTables
- Physics Sand Block Texture (pale yellow-beige speckled 16x16 sand tile) / MATERIAL_SAND granular particle material
- Physics Lava Bucket Texture (16x16 bucket item icon) / Lava Material (MATERIAL_LAVA)
- Physics Slime Bucket Texture (16x16 bucket with green slime) / Slime material (MATERIAL_SLIME)
- Physics Water Bucket texture (pixel-art bucket with water) / Water material (MATERIAL_WATER)
- MutableBlockPos
- Physics Buckets and Physics Sand Items
- vector3fc

## God Nodes (most connected - your core abstractions)
1. `FluidSimulation` - 126 edges
2. `ParticleSoA` - 58 edges
3. `PbfSolver` - 54 edges
4. `FluidDensityField` - 54 edges
5. `ChunkVoxelCache` - 46 edges
6. `ServerSimulations` - 45 edges
7. `ParticleBatchRenderer` - 41 edges
8. `MarchingCubesMesher` - 37 edges
9. `ParticleMesher` - 37 edges
10. `WorkerPool` - 33 edges

## Surprising Connections (you probably didn't know these)
- `/fluidphysics Commands` --references--> `ServerSimulations`  [INFERRED]
  README.md → Plan.md
- `Solver Phase Timing Breakdown (neighbours 33%, density 41%)` --references--> `PbfSolver`  [EXTRACTED]
  docs/perf/step-6a-baseline.md → Plan.md
- `CLAUDE.md Project Instructions` --references--> `ChunkVoxelCache`  [EXTRACTED]
  CLAUDE.md → Plan.md
- `Zero Heap Allocation / No Entity per Particle Rule` --rationale_for--> `ParticleSoA`  [EXTRACTED]
  CLAUDE.md → Plan.md
- `FabricPlatformHelper` --implements--> `IPlatformHelper`  [EXTRACTED]
  fabric/src/main/java/dev/tore/fluidphysics/fabric/platform/FabricPlatformHelper.java → common/src/main/java/dev/tore/fluidphysics/platform/IPlatformHelper.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Core physics pipeline** — realistic_water_mod_plan_pbfsolver, realistic_water_mod_plan_particlesoa, realistic_water_mod_plan_spatialhashgrid, realistic_water_mod_plan_chunkvoxelcache [INFERRED 0.75]
- **Smooth surface render pipeline** — realistic_water_mod_plan_particlebatchrenderer, realistic_water_mod_plan_particlemesher, realistic_water_mod_plan_fluiddensityfield, realistic_water_mod_plan_marchingcubesmesher, realistic_water_mod_plan_marchingcubestables, realistic_water_mod_plan_smoothfluidrenderer [INFERRED 0.85]
- **Entity and world effects on particles** — realistic_water_mod_plan_boatmixin, realistic_water_mod_plan_fallingblockentitymixin, realistic_water_mod_plan_explosionmixin, realistic_water_mod_plan_entitycontact [INFERRED 0.75]

## Communities (83 total, 28 thin omitted)

### Community 0 - "FluidDensityField / .addToBin()"
Cohesion: 0.05
Nodes (9): FluidDensityField, MarchingCubesMesher, FluidDensityFieldTest, MarchingCubesMesherTest, MeshReport, Position, Scene, Snapshot (+1 more)

### Community 1 - "arraylist / assertequals"
Cohesion: 0.06
Nodes (44): arraylist, assertequals, assertfalse, assertnotequals, assertnotnull, assertnull, asserttrue, assumetrue (+36 more)

### Community 2 - "BlockEnvironment / .packedLight()"
Cohesion: 0.09
Nodes (7): BlockEnvironment, ParticleMesher, VertexSink, Override, ParticleMesherTest, Recorder, VertexCounter

### Community 3 - "StatsReport.java / StatsReport"
Cohesion: 0.07
Nodes (9): StatsReport, AllocationMeter, HotSpot, RollingSamples, SimulationStats, StatsReportTest, AllocationMeterTest, RollingSamplesTest (+1 more)

### Community 4 - "BoatBuoyancy / .apply()"
Cohesion: 0.10
Nodes (8): BoatBuoyancy, Condensation, World, EntityContact, Sample, FallingBlockSplash, BlockOccupancy, net.minecraft.world.entity.Entity

### Community 5 - "Exception / fractions"
Cohesion: 0.08
Nodes (46): Exception, fractions, functools, sys, all_face_segments(), boundary_loops(), build_edges(), build_faces() (+38 more)

### Community 6 - "SmoothFluidRenderer.java / GlossyLook"
Cohesion: 0.13
Nodes (6): GlossyLook, SmoothFluidRenderer, Override, Recorder, SmoothFluidRendererTest, VertexCounter

### Community 7 - "blockhitresult / clipcontext"
Cohesion: 0.11
Nodes (20): blockhitresult, clipcontext, ParticlePickup, Override, PhysicsBucketItem, BucketItemMixin, gameevent, hitresult (+12 more)

### Community 8 - ".removeOutsideLoadedChunks() / .markRestInCluster()"
Cohesion: 0.11
Nodes (3): FluidSimulation, World, ParticleChunkGuard

### Community 9 - "at / .swim()"
Cohesion: 0.12
Nodes (19): at, ExplosionPush, BoatMixin, EntityMixin, ExplosionMixin, FallingBlockEntityMixin, LivingEntityMixin, entity (+11 more)

### Community 11 - "com.mojang.serialization.MapCodec / PhysicsLightBlock.java"
Cohesion: 0.16
Nodes (11): com.mojang.serialization.MapCodec, Override, PhysicsLightBlock, Simulations, LevelMixin, function, net.minecraft.core.BlockPos, net.minecraft.world.level.block.RenderShape (+3 more)

### Community 13 - "IPlatformHelper.java / IPlatformHelper"
Cohesion: 0.10
Nodes (12): IPlatformHelper, FabricPlatformHelper, Override, fabricloader, fmlloader, fmlpaths, modlist, Override (+4 more)

### Community 14 - ".pack() / ChunkVoxelCache.java"
Cohesion: 0.11
Nodes (12): ChunkVoxelCache, Column, MutableBlockPos, it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap, it.unimi.dsi.fastutil.objects.ObjectArrayList, it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap, levelchunksection (+4 more)

### Community 15 - "blockbehaviour / blockitem"
Cohesion: 0.15
Nodes (13): blockbehaviour, blockitem, TicketType, ModRegistry, consumer, Override, net.minecraft.resources.Identifier, net.minecraft.resources.ResourceKey (+5 more)

### Community 16 - "SolverStepBenchmark.java / SolverStepBenchmark"
Cohesion: 0.11
Nodes (19): SolverStepBenchmark, iterationtype, level, locale, mode, org.openjdk.jmh.annotations.Benchmark, org.openjdk.jmh.annotations.BenchmarkMode, org.openjdk.jmh.annotations.Fork (+11 more)

### Community 17 - "Floodgates / .dequeue()"
Cohesion: 0.15
Nodes (6): Floodgates, World, FakeWorld, FloodgatesTest, Override, it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue

### Community 18 - "abstractboat / blockpos"
Cohesion: 0.17
Nodes (10): abstractboat, blockpos, ParticleSoA, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap, it.unimi.dsi.fastutil.longs.LongArrayList, it.unimi.dsi.fastutil.longs.LongOpenHashSet, mth, net.minecraft.world.phys.AABB (+2 more)

### Community 19 - "bytebufcodecs / FluidPhysicsCommon.java"
Cohesion: 0.18
Nodes (14): bytebufcodecs, FluidPhysicsCommon, FluidClearPayload, Override, Type, FluidImpulsePayload, Override, Type (+6 more)

### Community 20 - "CauldronFilling / .accepts()"
Cohesion: 0.18
Nodes (5): CauldronFilling, World, CauldronFillingTest, FakeWorld, Override

### Community 21 - "ForcedCondensation / .collectCandidates()"
Cohesion: 0.18
Nodes (5): ForcedCondensation, FakeWorld, ForcedCondensationTest, Override, it.unimi.dsi.fastutil.longs.LongSet

### Community 22 - ".networkLine() / Type"
Cohesion: 0.21
Nodes (3): Type, NetworkStats, NetworkStatsTest

### Community 24 - "buildcreativemodetabcontentsevent / clienttickevent"
Cohesion: 0.10
Nodes (18): buildcreativemodetabcontentsevent, clienttickevent, dist, leveltickevent, minecraft, neoforge, FluidPhysicsNeoForge, FluidPhysicsNeoForgeClient (+10 more)

### Community 26 - "blockposargument / chunkpos"
Cohesion: 0.11
Nodes (9): blockposargument, chunkpos, commands, FluidNetworking, MaterialProperties, integerargumenttype, predicate, sectionpos (+1 more)

### Community 27 - "atomicinteger / .workerThreads()"
Cohesion: 0.19
Nodes (5): atomicinteger, FunctionalInterface, RangeTask, WorkerPool, locksupport

### Community 28 - "PhysicsSandBlock.java / Override"
Cohesion: 0.21
Nodes (8): Override, PhysicsSandBlock, fallingblock, net.minecraft.core.Direction, net.minecraft.util.RandomSource, net.minecraft.world.level.Explosion, net.minecraft.world.level.LevelReader, net.minecraft.world.level.ScheduledTickAccess

### Community 29 - "ClientSimulation.java / ClientSimulation"
Cohesion: 0.23
Nodes (3): ClientSimulation, Override, net.minecraft.client.multiplayer.ClientLevel

### Community 30 - "com.mojang.blaze3d.vertex.PoseStack / BoatTilt.java"
Cohesion: 0.24
Nodes (9): com.mojang.blaze3d.vertex.PoseStack, BoatTilt, BoatRendererMixin, java.util.WeakHashMap, net.minecraft.client.renderer.entity.AbstractBoatRenderer, net.minecraft.client.renderer.entity.state.BoatRenderState, net.minecraft.client.renderer.state.CameraRenderState, net.minecraft.client.renderer.SubmitNodeCollector (+1 more)

### Community 31 - ".x() / .z()"
Cohesion: 0.17
Nodes (4): ParticleTickets, ServerResync, it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap, net.minecraft.server.level.TicketType

### Community 33 - ".removeMarked() / FunctionalInterface"
Cohesion: 0.24
Nodes (5): FunctionalInterface, RemovalSink, CondensationTest, FakeWorld, Override

### Community 34 - "arrays / cell_size"
Cohesion: 0.12
Nodes (13): arrays, cell_size, cohesion, collision_class, granular, gravity, inv_cell_size, node_stride_y (+5 more)

### Community 38 - "BlockCache / .begin()"
Cohesion: 0.16
Nodes (5): BlockCache, BufferSink, Override, MutableBlockPos, net.minecraft.client.Minecraft

### Community 39 - "ClientConfig.java / Services.java"
Cohesion: 0.22
Nodes (8): Services, files, inputstream, ioexception, outputstream, path, properties, serviceloader

### Community 40 - ".clear() / .clear()"
Cohesion: 0.15
Nodes (4): MutableBlockPos, World, ServerInteractions, Fluid

### Community 41 - "os / pil"
Cohesion: 0.22
Nodes (13): os, pil, bucket(), clamp(), main(), physics_sand(), A metal bucket holding {contents}, with particle droplets of {droplet} hovering…, Generates the mod's 16x16 textures procedurally (clean-room: no external art).… (+5 more)

### Community 42 - "abstractcauldronblock / blocks"
Cohesion: 0.19
Nodes (10): abstractcauldronblock, blocks, blockstate, MutableBlockPos, LavaLight, fluids, fluidstate, layeredcauldronblock (+2 more)

### Community 43 - "ClientConfig / .ClientConfig()"
Cohesion: 0.27
Nodes (4): ClientConfig, RenderMode, CUBES, SMOOTH

### Community 45 - "Grid Fixed Cost (65536 buckets) / Solver Phase Timing Breakdown (neighbours 33%, density 41%)"
Cohesion: 0.19
Nodes (13): Grid Fixed Cost (65536 buckets), Solver Phase Timing Breakdown (neighbours 33%, density 41%), SolverStepBenchmark baseline results, CLAUDE.md Project Instructions, Clean-Room Policy and Apache-2.0 License, Event + Coarse Grid Sync (no per-particle network data), Zero Heap Allocation / No Entity per Particle Rule, JMH SolverStepBenchmark (+5 more)

### Community 46 - "clientcommandregistrationcallback / clientplaynetworking"
Cohesion: 0.20
Nodes (10): clientcommandregistrationcallback, clientplaynetworking, clienttickevents, FluidCondensePayload, Override, Type, FluidPhysicsFabricClient, fabricclientcommandsource (+2 more)

### Community 47 - "Placer / .place()"
Cohesion: 0.44
Nodes (3): Placer, StoneFormation, StoneFormationTest

### Community 48 - "biconsumer / com.mojang.brigadier.CommandDispatcher"
Cohesion: 0.25
Nodes (6): biconsumer, com.mojang.brigadier.CommandDispatcher, ClientStatsCommand, dev.tore.fluidphysics.physics.FluidSimulation, literalargumentbuilder, net.minecraft.network.chat.Component

### Community 50 - "FluidSpawnPayload / .type()"
Cohesion: 0.27
Nodes (4): FluidSpawnPayload, Override, Type, PayloadCodecTest

### Community 51 - "ChunkKeys.java / ChunkKeys"
Cohesion: 0.24
Nodes (5): ChunkKeys, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap, iterator, java.util.function.LongConsumer, longiterator

### Community 52 - ".ofBlock() / MutableBlockPos"
Cohesion: 0.22
Nodes (4): MutableBlockPos, Override, ServerFloodgates, World

### Community 53 - "builtinregistries / commandregistrationcallback"
Cohesion: 0.22
Nodes (9): builtinregistries, commandregistrationcallback, FluidPhysicsFabric, itemgroupevents, net.fabricmc.api.ModInitializer, payloadtyperegistry, registry, serverlifecycleevents (+1 more)

### Community 54 - "com.mojang.blaze3d.vertex.BufferBuilder / .beginBuffer()"
Cohesion: 0.36
Nodes (3): com.mojang.blaze3d.vertex.BufferBuilder, net.minecraft.client.renderer.rendertype.RenderType, org.joml.Vector3fc

### Community 57 - "Floodgates / FluidClearPayload"
Cohesion: 0.20
Nodes (10): Floodgates, FluidClearPayload, FluidCondensePayload, FluidImpulsePayload, /fluidphysics command, FluidSpawnPayload, No per-particle network sync rule, ServerSimulations (+2 more)

### Community 58 - "biomecolors / camera"
Cohesion: 0.22
Nodes (8): biomecolors, camera, com.mojang.blaze3d.vertex.ByteBufferBuilder, hashcommon, lightlayer, lighttexture, meshdata, rendertypes

### Community 60 - "FluidSyncPayload / .read()"
Cohesion: 0.32
Nodes (3): FluidSyncPayload, Override, Type

### Community 61 - "ChunkVoxelCache / Render interpolation with DeltaTracker partial tick"
Cohesion: 0.32
Nodes (8): ChunkVoxelCache, Render interpolation with DeltaTracker partial tick, FluidDensityField, MarchingCubesMesher, MarchingCubesTables, ParticleBatchRenderer, ParticleMesher, SmoothFluidRenderer

### Community 63 - "FluidReactionsTest / .particlesReactWithVanillaFluids()"
Cohesion: 0.43
Nodes (3): FluidReactionsTest, Override, Recorder

### Community 64 - "Step 6a Solver Performance Baseline / Plan.md"
Cohesion: 0.33
Nodes (5): Step 6a Solver Performance Baseline, Identifier vs ResourceLocation rename, ModRegistry (itemProps / blockProps), setId requirement from 1.21.2, Stonecutter build layout

### Community 66 - "Boat class hierarchy (Boat vs AbstractBoat) / BoatMixin (buoyancy)"
Cohesion: 0.40
Nodes (6): Boat class hierarchy (Boat vs AbstractBoat), BoatMixin (buoyancy), EntityContact, ExplosionMixin, FallingBlockEntityMixin, FluidSimulation

### Community 67 - "FluidReactions (lava and water reaction) / Lava (material 1)"
Cohesion: 0.40
Nodes (6): FluidReactions (lava and water reaction), Lava (material 1), Material Behavior Matrix, Sand (material 3), Slime (material 2), Water (material 0)

### Community 69 - "Condensation (re-blockification) / ForcedCondensation"
Cohesion: 0.40
Nodes (5): Condensation (re-blockification), ForcedCondensation, 64-particle mass conservation rule, ParticleChunkGuard, ServerInteractions

### Community 70 - "gradlew / gradlew script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **27 isolated node(s):** `Grid Fixed Cost (65536 buckets)`, `Solver Phase Timing Breakdown (neighbours 33%, density 41%)`, `SolverStepBenchmark baseline results`, `/fluidphysics Commands`, `Physics Buckets and Physics Sand Items` (+22 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 272 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **28 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `FluidSimulation` connect `.removeOutsideLoadedChunks() / .markRestInCluster()` to `arraylist / assertequals`, `StatsReport.java / StatsReport`, `BoatBuoyancy / .apply()`, `blockhitresult / clipcontext`, `.clear() / .activeCount()`, `com.mojang.serialization.MapCodec / PhysicsLightBlock.java`, `PbfSolver / .applyCorrectionsAndCollide()`, `.pack() / ChunkVoxelCache.java`, `abstractboat / blockpos`, `bytebufcodecs / FluidPhysicsCommon.java`, `CauldronFilling / .accepts()`, `ForcedCondensation / .collectCandidates()`, `blockposargument / chunkpos`, `atomicinteger / .workerThreads()`, `PhysicsSandBlock.java / Override`, `ClientSimulation.java / ClientSimulation`, `.x() / .z()`, `.removeMarked() / FunctionalInterface`, `arrays / cell_size`, `.sendToLevel() / .sendToTracking()`, `abstractcauldronblock / blocks`, `ChunkKeys.java / ChunkKeys`, `FluidReactions / .cell()`, `EntityContactTest / .aBoxFarFromAllParticlesTouchesNothing()`, `.advance() / .applyWakes()`, `.spawn() / .pourOnServer()`?**
  _High betweenness centrality (0.186) - this node is a cross-community bridge._
- **Why does `ParticleSoA` connect `abstractboat / blockpos` to `arraylist / assertequals`, `BlockEnvironment / .packedLight()`, `StatsReport.java / StatsReport`, `BoatBuoyancy / .apply()`, `blockhitresult / clipcontext`, `.removeOutsideLoadedChunks() / .markRestInCluster()`, `.clear() / .activeCount()`, `PbfSolver / .applyCorrectionsAndCollide()`, `.pack() / ChunkVoxelCache.java`, `blockbehaviour / blockitem`, `SolverStepBenchmark.java / SolverStepBenchmark`, `bytebufcodecs / FluidPhysicsCommon.java`, `ForcedCondensation / .collectCandidates()`, `.add() / .jitter()`, `blockposargument / chunkpos`, `PhysicsSandBlock.java / Override`, `ClientSimulation.java / ClientSimulation`, `arrays / cell_size`, `abstractcauldronblock / blocks`, `ChunkKeys.java / ChunkKeys`, `FluidReactions / .cell()`, `.advance() / .applyWakes()`?**
  _High betweenness centrality (0.086) - this node is a cross-community bridge._
- **Why does `ParticleBatchRenderer` connect `.renderLines() / ParticleBatchRenderer` to `FluidDensityField / .addToBin()`, `BlockEnvironment / .packedLight()`, `CubeFilter / .test()`, `BlockCache / .begin()`, `SmoothFluidRenderer.java / GlossyLook`, `ClientConfig / .ClientConfig()`, `clientcommandregistrationcallback / clientplaynetworking`, `biconsumer / com.mojang.brigadier.CommandDispatcher`, `com.mojang.blaze3d.vertex.BufferBuilder / .beginBuffer()`, `buildcreativemodetabcontentsevent / clienttickevent`, `biomecolors / camera`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `FluidDensityField` (e.g. with `.emittingAllocatesNothingOnceGrown()` and `.meshOf()`) actually correct?**
  _`FluidDensityField` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Grid Fixed Cost (65536 buckets)`, `Solver Phase Timing Breakdown (neighbours 33%, density 41%)`, `SolverStepBenchmark baseline results` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `FluidDensityField / .addToBin()` be split into smaller, more focused modules?**
  _Cohesion score 0.05229610314356077 - nodes in this community are weakly interconnected._
- **Should `arraylist / assertequals` be split into smaller, more focused modules?**
  _Cohesion score 0.05744888023369036 - nodes in this community are weakly interconnected._