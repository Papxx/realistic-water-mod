# Graph Report - realistic-water-mod  (2026-11-17)

## Corpus Check
- 133 files · ~78,318 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 12 file(s) not represented in the graph (top: .properties 4, (none) 3, .IPlatformHelper 2)

## Summary
- 1261 nodes · 3683 edges · 70 communities (41 shown, 29 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 616 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Condensation & Particle Lifecycle
- Particle Meshing & Block Environment
- Bucket Pickup & Raycast
- Docs, Plan & Perf Baseline
- Cauldron Filling
- Stone Formation (Obsidian)
- Mixins: Boats, Buckets, Splash
- Client Simulation & Events
- Boat Buoyancy & Cluster Condense
- PBF Solver Core
- Material Properties & Mesher Tests
- Test Fixtures & Voxel Setup
- Simulation Stats Reporting
- Physics Sand Block
- Common Init & Payload Codecs
- Floodgates
- Solver Benchmark (JMH)
- Entity Contact
- Commands & Networking
- Render Block Cache
- Platform Helper Services
- Reactions & Feature Classes
- Network Stats
- Spawn/Condense Payloads
- Boat Tilt Rendering
- Fluidphysics Command
- ChunkVoxelCache
- Command Tests
- Collision Class & Mesher Stats Tests
- Server Interactions & Cauldron Cache
- Physics Light Block
- Config & Service Loading
- Client Stats Command
- Server Floodgates & Pour
- Explosion Push & Simulations
- Texture Generator Script
- Forced Condensation
- Spatial Hash Grid
- Server Resync
- Statistics Helpers
- Fabric Client Hooks
- Solver Worker Pool
- Particle Batch Renderer
- Chunk Keys & Guard
- NeoForge Client
- Registry & Particle Tickets
- Fabric Entrypoint
- Fluid Config Persistence
- Impulse Payload & Net Stats Test
- Payload Codec Tests
- Solver Substep Tasks
- Pool Close Helpers
- Entity Contact Tests
- Material Properties
- Allocation Meter Tests
- Voxel Cache Tests
- Fabric Platform Helper
- Stats Report
- Gradle Wrapper
- Sand Texture
- Lava Bucket Texture
- Slime Bucket Texture
- Water Bucket Texture
- Water+Lava Reaction

## God Nodes (most connected - your core abstractions)
1. `FluidSimulation` - 128 edges
2. `ParticleSoA` - 62 edges
3. `PbfSolver` - 55 edges
4. `ChunkVoxelCache` - 46 edges
5. `ServerSimulations` - 45 edges
6. `ParticleMesher` - 36 edges
7. `WorkerPool` - 33 edges
8. `BlockOccupancy` - 31 edges
9. `RollingSamples` - 26 edges
10. `ParticleMesherTest` - 25 edges

## Surprising Connections (you probably didn't know these)
- `FabricPlatformHelper` --implements--> `IPlatformHelper`  [EXTRACTED]
  fabric/src/main/java/dev/tore/fluidphysics/fabric/platform/FabricPlatformHelper.java → common/src/main/java/dev/tore/fluidphysics/platform/IPlatformHelper.java
- `CLAUDE.md Project Instructions` --references--> `IPlatformHelper (ServiceLoader platform pattern)`  [EXTRACTED]
  CLAUDE.md → Plan.md
- `/fluidphysics Commands` --references--> `ServerSimulations`  [INFERRED]
  README.md → Plan.md
- `NeoForgePlatformHelper` --implements--> `IPlatformHelper`  [EXTRACTED]
  neoforge/src/main/java/dev/tore/fluidphysics/neoforge/platform/NeoForgePlatformHelper.java → common/src/main/java/dev/tore/fluidphysics/platform/IPlatformHelper.java
- `CLAUDE.md Project Instructions` --references--> `Plan.md Implementation Plan`  [EXTRACTED]
  CLAUDE.md → Plan.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Physics Core Pipeline (SoA, grid, voxel cache, solver)** — realistic_water_mod_plan_particlesoa, realistic_water_mod_plan_spatialhashgrid, realistic_water_mod_plan_chunkvoxelcache, realistic_water_mod_plan_pbfsolver [EXTRACTED 0.95]
- **Event-based multiplayer sync** — realistic_water_mod_plan_serversimulations, realistic_water_mod_plan_networking_payloads, realistic_water_mod_plan_clientsimulation, realistic_water_mod_claude_event_coarse_sync [EXTRACTED 0.95]
- **Step 8 smooth iso-surface rendering pipeline** — realistic_water_mod_plan_fluiddensityfield, realistic_water_mod_plan_marchingcubesmesher, realistic_water_mod_plan_smoothfluidrenderer [EXTRACTED 0.95]

## Communities (70 total, 29 thin omitted)

### Community 0 - "Condensation & Particle Lifecycle"
Cohesion: 0.06
Nodes (15): Condensation, FunctionalInterface, RemovalSink, CondensationTest, FakeWorld, Override, FluidReactionsTest, Override (+7 more)

### Community 1 - "Particle Meshing & Block Environment"
Cohesion: 0.09
Nodes (7): BlockEnvironment, ParticleMesher, VertexSink, Override, ParticleMesherTest, Recorder, VertexCounter

### Community 2 - "Bucket Pickup & Raycast"
Cohesion: 0.07
Nodes (25): blockhitresult, clipcontext, ParticlePickup, Override, PhysicsBucketItem, TicketType, ModRegistry, Override (+17 more)

### Community 3 - "Docs, Plan & Perf Baseline"
Cohesion: 0.05
Nodes (46): Step 6a Solver Performance Baseline, Grid Fixed Cost (65536 buckets), Solver Phase Timing Breakdown (neighbours 33%, density 41%), SolverStepBenchmark baseline results, CLAUDE.md Project Instructions, Clean-Room Policy and Apache-2.0 License, Event + Coarse Grid Sync (no per-particle network data), Zero Heap Allocation / No Entity per Particle Rule (+38 more)

### Community 4 - "Cauldron Filling"
Cohesion: 0.12
Nodes (7): CauldronFilling, World, FluidReactions, Sink, CauldronFillingTest, FakeWorld, Override

### Community 5 - "Stone Formation (Obsidian)"
Cohesion: 0.10
Nodes (9): Placer, StoneFormation, World, ParticleChunkGuard, MutableBlockPos, World, ServerInteractions, StoneFormationTest (+1 more)

### Community 6 - "Mixins: Boats, Buckets, Splash"
Cohesion: 0.12
Nodes (21): at, FallingBlockSplash, BoatMixin, BucketItemMixin, ExplosionMixin, FallingBlockEntityMixin, LevelMixin, LivingEntityMixin (+13 more)

### Community 7 - "Client Simulation & Events"
Cohesion: 0.15
Nodes (5): ClientSimulation, FluidSimulation, Override, it.unimi.dsi.fastutil.longs.LongSet, net.minecraft.client.multiplayer.ClientLevel

### Community 8 - "Boat Buoyancy & Cluster Condense"
Cohesion: 0.15
Nodes (3): BoatBuoyancy, World, BlockOccupancy

### Community 10 - "Material Properties & Mesher Tests"
Cohesion: 0.19
Nodes (16): arraylist, assertequals, assertfalse, assertnull, asserttrue, assumetrue, SpatialHashGridTest, hashset (+8 more)

### Community 13 - "Physics Sand Block"
Cohesion: 0.17
Nodes (11): Override, PhysicsSandBlock, fallingblock, itemstack, net.minecraft.core.BlockPos, net.minecraft.core.Direction, net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.BlockGetter (+3 more)

### Community 14 - "Common Init & Payload Codecs"
Cohesion: 0.17
Nodes (13): assertnotequals, bytebufcodecs, FluidPhysicsCommon, FluidStreamCodecs, FluidSyncPayload, Override, Type, io.netty.buffer.ByteBuf (+5 more)

### Community 15 - "Floodgates"
Cohesion: 0.15
Nodes (6): Floodgates, World, FakeWorld, FloodgatesTest, Override, it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue

### Community 16 - "Solver Benchmark (JMH)"
Cohesion: 0.12
Nodes (19): SolverStepBenchmark, iterationtype, level, locale, mode, org.openjdk.jmh.annotations.Benchmark, org.openjdk.jmh.annotations.BenchmarkMode, org.openjdk.jmh.annotations.Fork (+11 more)

### Community 17 - "Entity Contact"
Cohesion: 0.19
Nodes (7): abstractboat, EntityContact, Sample, EntityMixin, net.minecraft.world.entity.Entity, net.minecraft.world.phys.AABB, soundevents

### Community 18 - "Commands & Networking"
Cohesion: 0.16
Nodes (8): blockposargument, commands, FluidNetworking, ServerSimulations, integerargumenttype, literalargumentbuilder, net.minecraft.server.level.ServerLevel, predicate

### Community 19 - "Render Block Cache"
Cohesion: 0.14
Nodes (6): com.mojang.blaze3d.vertex.BufferBuilder, BlockCache, BufferSink, MutableBlockPos, Override, ParticleBatchRenderer

### Community 20 - "Platform Helper Services"
Cohesion: 0.11
Nodes (10): IPlatformHelper, fabricloader, fmlloader, fmlpaths, modlist, Override, NeoForgePlatformHelper, packetdistributor (+2 more)

### Community 21 - "Reactions & Feature Classes"
Cohesion: 0.20
Nodes (8): blockpos, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap, it.unimi.dsi.fastutil.longs.LongArrayList, it.unimi.dsi.fastutil.longs.LongOpenHashSet, iterator, longiterator, mth

### Community 23 - "Spawn/Condense Payloads"
Cohesion: 0.13
Nodes (15): buildcreativemodetabcontentsevent, FluidCondensePayload, Override, Type, FluidSpawnPayload, Override, Type, leveltickevent (+7 more)

### Community 24 - "Boat Tilt Rendering"
Cohesion: 0.22
Nodes (10): com.mojang.blaze3d.vertex.PoseStack, BoatTilt, BoatRendererMixin, java.util.WeakHashMap, net.minecraft.client.renderer.entity.AbstractBoatRenderer, net.minecraft.client.renderer.entity.state.BoatRenderState, net.minecraft.client.renderer.state.CameraRenderState, net.minecraft.client.renderer.SubmitNodeCollector (+2 more)

### Community 26 - "ChunkVoxelCache"
Cohesion: 0.16
Nodes (4): ChunkVoxelCache, Column, MutableBlockPos, net.minecraft.world.level.chunk.LevelChunk

### Community 27 - "Command Tests"
Cohesion: 0.14
Nodes (12): assertnotnull, bootstrap, collectors, com.mojang.brigadier.tree.CommandNode, commanddispatcher, FluidPhysicsCommandTest, emptyblockgetter, liquidblock (+4 more)

### Community 28 - "Collision Class & Mesher Stats Tests"
Cohesion: 0.14
Nodes (9): arrays, cohesion, collision_class, SimulationTesting, granular, gravity, restitution, viscosity (+1 more)

### Community 29 - "Server Interactions & Cauldron Cache"
Cohesion: 0.14
Nodes (14): abstractcauldronblock, blockstate, fluids, fluidstate, it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap, it.unimi.dsi.fastutil.objects.ObjectArrayList, it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap (+6 more)

### Community 30 - "Physics Light Block"
Cohesion: 0.19
Nodes (8): blockbehaviour, com.mojang.serialization.MapCodec, Override, PhysicsLightBlock, MutableBlockPos, LavaLight, net.minecraft.util.RandomSource, net.minecraft.world.level.block.RenderShape

### Community 31 - "Config & Service Loading"
Cohesion: 0.14
Nodes (10): chunkpos, Services, files, inputstream, ioexception, outputstream, properties, sectionpos (+2 more)

### Community 32 - "Client Stats Command"
Cohesion: 0.18
Nodes (6): com.mojang.brigadier.CommandDispatcher, ClientStatsCommand, AllocationMeter, HotSpot, net.minecraft.network.chat.Component, ThreadMXBean

### Community 33 - "Server Floodgates & Pour"
Cohesion: 0.21
Nodes (4): MutableBlockPos, Override, ServerFloodgates, World

### Community 34 - "Explosion Push & Simulations"
Cohesion: 0.19
Nodes (5): ExplosionPush, Simulations, function, net.minecraft.world.level.Level, serverlevel

### Community 35 - "Texture Generator Script"
Cohesion: 0.20
Nodes (14): os, pil, random, bucket(), clamp(), main(), physics_sand(), A metal bucket holding {contents}, with particle droplets of {droplet} hovering… (+6 more)

### Community 38 - "Server Resync"
Cohesion: 0.24
Nodes (3): ServerResync, it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap, net.minecraft.server.level.ServerPlayer

### Community 40 - "Fabric Client Hooks"
Cohesion: 0.20
Nodes (10): clientcommandregistrationcallback, clientplaynetworking, clienttickevents, FluidClearPayload, Override, Type, FluidPhysicsFabricClient, fabricclientcommandsource (+2 more)

### Community 41 - "Solver Worker Pool"
Cohesion: 0.25
Nodes (3): atomicinteger, WorkerPool, locksupport

### Community 42 - "Particle Batch Renderer"
Cohesion: 0.18
Nodes (10): biomecolors, camera, com.mojang.blaze3d.vertex.ByteBufferBuilder, hashcommon, lightlayer, lighttexture, meshdata, net.minecraft.client.renderer.rendertype.RenderType (+2 more)

### Community 43 - "Chunk Keys & Guard"
Cohesion: 0.27
Nodes (4): blocks, ChunkKeys, java.util.function.LongConsumer, net.minecraft.client.Minecraft

### Community 44 - "NeoForge Client"
Cohesion: 0.20
Nodes (9): clienttickevent, dist, minecraft, neoforge, FluidPhysicsNeoForge, FluidPhysicsNeoForgeClient, net.neoforged.fml.common.Mod, registerclientcommandsevent (+1 more)

### Community 45 - "Registry & Particle Tickets"
Cohesion: 0.24
Nodes (6): biconsumer, blockitem, ParticleTickets, consumer, net.minecraft.server.level.TicketType, registries

### Community 46 - "Fabric Entrypoint"
Cohesion: 0.22
Nodes (9): builtinregistries, commandregistrationcallback, FluidPhysicsFabric, itemgroupevents, net.fabricmc.api.ModInitializer, payloadtyperegistry, registry, serverlifecycleevents (+1 more)

### Community 48 - "Impulse Payload & Net Stats Test"
Cohesion: 0.31
Nodes (4): FluidImpulsePayload, Override, Type, NetworkStatsTest

### Community 60 - "Gradle Wrapper"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **27 isolated node(s):** `ModRegistry (itemProps/blockProps helpers)`, `IPlatformHelper (ServiceLoader platform pattern)`, `WorkerPool (fixed solver thread pool)`, `Material Behavior Matrix (Water/Lava/Slime/Sand)`, `Cauldron Filling` (+22 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 233 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **29 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `FluidSimulation` connect `Client Simulation & Events` to `Condensation & Particle Lifecycle`, `Bucket Pickup & Raycast`, `Cauldron Filling`, `Stone Formation (Obsidian)`, `Boat Buoyancy & Cluster Condense`, `PBF Solver Core`, `Material Properties & Mesher Tests`, `Simulation Stats Reporting`, `Physics Sand Block`, `Entity Contact`, `Commands & Networking`, `Reactions & Feature Classes`, `Spawn/Condense Payloads`, `ChunkVoxelCache`, `Collision Class & Mesher Stats Tests`, `Server Interactions & Cauldron Cache`, `Client Stats Command`, `Server Floodgates & Pour`, `Explosion Push & Simulations`, `Forced Condensation`, `Server Resync`, `Solver Worker Pool`, `Particle Batch Renderer`, `Chunk Keys & Guard`, `Entity Contact Tests`?**
  _High betweenness centrality (0.272) - this node is a cross-community bridge._
- **Why does `ParticleSoA` connect `Forced Condensation` to `Condensation & Particle Lifecycle`, `Particle Meshing & Block Environment`, `Bucket Pickup & Raycast`, `Cauldron Filling`, `Client Simulation & Events`, `Boat Buoyancy & Cluster Condense`, `PBF Solver Core`, `Material Properties & Mesher Tests`, `Test Fixtures & Voxel Setup`, `Physics Sand Block`, `Solver Benchmark (JMH)`, `Entity Contact`, `Commands & Networking`, `Reactions & Feature Classes`, `Spawn/Condense Payloads`, `ChunkVoxelCache`, `Collision Class & Mesher Stats Tests`, `Server Interactions & Cauldron Cache`, `Client Stats Command`, `Solver Worker Pool`, `Particle Batch Renderer`, `Chunk Keys & Guard`, `Registry & Particle Tickets`?**
  _High betweenness centrality (0.120) - this node is a cross-community bridge._
- **Why does `PbfSolver` connect `PBF Solver Core` to `Client Stats Command`, `Forced Condensation`, `Spatial Hash Grid`, `Client Simulation & Events`, `Solver Worker Pool`, `Material Properties & Mesher Tests`, `Test Fixtures & Voxel Setup`, `Simulation Stats Reporting`, `Solver Benchmark (JMH)`, `Solver Substep Tasks`, `ChunkVoxelCache`, `Solver Profiling Metrics`, `Collision Class & Mesher Stats Tests`?**
  _High betweenness centrality (0.067) - this node is a cross-community bridge._
- **What connects `ModRegistry (itemProps/blockProps helpers)`, `IPlatformHelper (ServiceLoader platform pattern)`, `WorkerPool (fixed solver thread pool)` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Condensation & Particle Lifecycle` be split into smaller, more focused modules?**
  _Cohesion score 0.06337222603523221 - nodes in this community are weakly interconnected._
- **Should `Particle Meshing & Block Environment` be split into smaller, more focused modules?**
  _Cohesion score 0.09265536723163842 - nodes in this community are weakly interconnected._
- **Should `Bucket Pickup & Raycast` be split into smaller, more focused modules?**
  _Cohesion score 0.07477288609364081 - nodes in this community are weakly interconnected._