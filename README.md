# Realistic Fluid & Granular Physics

**Water that splashes. Lava that oozes. Slime that stretches. Sand that piles up.**

Realistic Fluid & Granular Physics (`fluidphysics`) replaces Minecraft's cellular fluids and falling-sand behaviour with a real-time, particle-based simulation. Liquids flow around obstacles, spray from pipes, burst through broken dams and settle into piles, all while staying fast enough for multiplayer.

> **Status:** early development (v0.1.0). The physics core, networking, rendering, items and world interactions are in place: physics buckets and crumbling sand, condensation back into vanilla blocks, cauldrons, water and lava reactions, boats, falling blocks, explosions and effects on entities. Everything runs on all four targets and is covered by unit tests and in-game checks. Performance work (Plan.md §6) comes next. Features that are still planned are marked as such below. See [Plan.md](Plan.md) for the detailed specification and progress.

## Supported platforms

| Minecraft | Fabric | NeoForge |
|-----------|--------|----------|
| 1.21.1    | yes    | yes      |
| 1.21.11   | yes    | yes      |

Requires Java 21. The mod uses the official Mojang mappings, has no heavy runtime dependencies, and is built from a single codebase with [Stonecutter](https://stonecutter.kikugie.dev/).

## Features

### Four simulated materials

| Material | Behaviour |
|----------|-----------|
| **Water** | Position Based Fluids (PBF) with low viscosity. It splashes at high speed, forms foam and spray droplets, and pushes boats and entities with buoyancy. |
| **Lava** | High viscosity, so it creeps slowly. It glows, ignites entities, and turns into obsidian or cobblestone on contact with water. |
| **Slime** | Viscoelastic and sticky. It clings to walls and stairs, stretches along its motion, and bounces players and falling anvils. |
| **Sand** | Granular simulation with high friction. It stacks at a natural angle of repose (about 35°) and rolls down stairs. |

### Strict mass conservation

- One bucket or one full block equals exactly **64 particles** (a 4×4×4 grid). Nothing is ever duplicated. Particles only disappear where it makes physical sense: water that boils off cooling lava, a few lava particles that cool without forming a whole block, or a small leftover that merges into a vanilla water or lava source next to it.
- **Condensation:** when resting particles of one material add up to 64, in one block or spread over neighbouring blocks, they turn back into the matching vanilla block (water, lava, slime, sand) at the lowest free spot. This keeps the particle count and lag low.
- **Cauldrons** physically fill up with particles and raise the vanilla cauldron level.
- Physics buckets spawn particles, and an empty bucket can scoop them up again.

### Items

| Item | Use |
|------|-----|
| **Physics water / lava / slime bucket** | Pours 64 particles into the block you aim at and leaves an empty bucket. Nothing is poured if the particle limit is reached, so a bucket never loses part of its content. |
| **Empty bucket** (vanilla) | Picks up 64 particles of water, lava or slime near where you aim (within 2.5 blocks) before it looks for vanilla fluids. |
| **Physics sand** | Crumbles into 64 sand particles when the block below it is removed, when you mine it or when it is blown up. Resting sand turns back into vanilla sand. |

The buckets are in the *Tools & Utilities* creative tab, physics sand is in *Natural Blocks*.

### World interaction

- **Floodgates and dams:** every block change reaches the particles at once, so breaking glass, opening trapdoors or retracting pistons lets them flow immediately.
- **Water and lava react:** lava particles that touch water cool into obsidian or cobblestone, and water particles turn vanilla lava into stone.
- **Boats** float on water particles. Buoyancy is sampled at the boat's four corners, and streams carry the boat along. (Visible pitch and roll are planned.)
- **Anvils and TNT** displace fluid. Explosions push particles outward, and falling anvils splash through particles and bounce off slime.
- **Entities:** lava particles set entities on fire, water particles put fires out, cushion falls, carry entities along and keep items afloat, and slime bounces falling players and mobs back up. Jumping into particles splashes them away.
- Chunk unloads force condensation, so no particles are orphaned and mass survives restarts (planned).

### Smooth visuals

- Interpolated rendering at 60 to 240+ FPS on top of the 20 TPS simulation.
- Water in the biome's water colour that foams where it splashes and breaks into small white spray droplets, glowing lava that crusts over when it rests, translucent slime and grainy sand.
- Moving liquids stretch along their direction of motion while keeping their volume, so falling water streaks instead of tumbling as cubes.
- All particles are drawn in one batch per frame, sorted far to near so translucent water and slime blend correctly, with only the faces that point at the camera. Lighting follows the world's block and sky light.
- Rendering goes through a standard vanilla render type and the loaders' own render events, for compatibility with Sodium and Iris (not tested yet).

## Performance design

- Particles live in a pre-allocated **Structure of Arrays**. There are no entities and no per-particle objects.
- **Zero heap allocations** inside the tick loop.
- An O(N) counting-sort uniform grid handles neighbour search. Its cells are as large as the smoothing radius (0.35 blocks) and laid out spatially, so neighbouring cells sit next to each other in memory.
- A cached 4×4×4 sub-voxel collision mask per block replaces per-particle `getBlockState()` calls. It supports stairs, slabs, cauldrons and trapdoors. Chunk sections are read once on the main thread, and unloaded chunks are never loaded.
- 2 solver substeps per tick with 2 constraint iterations each. Every pass is split across a small fixed worker pool (half the CPU threads, at most 8) without allocating. Sleeping islands are planned.
- Hard cap of 32,768 particles. The runtime limit is configurable (client default 32,768, server default 8,192).
- Current measurement: 16,384 moving water particles take about 5 ms per tick with 6 threads on a 6-core desktop. The target is 3.5 ms.

### Physics notes

- Water, lava and slime use Position Based Fluids. Solid faces count as resting particles in the density estimate, and corrections are under-relaxed and mass-scaled by height, so pools keep their volume (94 to 96 % even 6 blocks deep) and come to rest, which condensation relies on.
- Sand uses particle contacts with Coulomb friction against other grains and against walls. A grain holds on a 30° slope and slides off at 45°, and poured sand forms a pile instead of a puddle.
- Particles are limited to 9.5 blocks per second, so they never skip a whole 0.25-block sub-voxel in one substep and can not tunnel through trapdoors.

## Multiplayer

Particle positions are never sent over the network. The server sends only small events (spawn, impulse, condense and clear, 0 to 23 bytes each) to the players who can see where they happen, and each client simulates its own particles. This keeps bandwidth tiny even with thousands of particles.

## Commands

All subcommands need operator rights (permission level 2) and act on your current dimension.

```
/fluidphysics stats                         Show particle counts, tick timings with their breakdown, allocations,
                                            the voxel cache and the packets sent
/fluidphysics clear                         Remove all particles
/fluidphysics spawn <material> [blocks] [pos]
                                            Fill blocks with particles (64 per block) at your position or pos
                                            material: water, lava, slime or sand; blocks: 1 to 512
/fluidphysics limit [n]                     Show or set the particle limit per dimension (0 to 32768)
```

`/fluidphysics_client stats` (no operator rights needed) shows the same for your own client's simulation, plus the render time per frame.

## Building

```bash
./gradlew build                       # all four targets, including the unit tests
./gradlew :fabric:1.21.1:build        # one specific target
./gradlew :neoforge:1.21.11:build
./gradlew :common:1.21.11:test        # physics unit tests and the solver benchmark only
```

The solver benchmark (`PbfSolverTest`) prints the median time per tick at 16,384 particles and fails on any heap allocation inside the step loop.

JMH benchmarks of the solver (4k, 16k and 32k particles, one thread and the default worker pool) live in `common/src/jmh`; run them with `./gradlew :common:1.21.11:jmh` (about 5 minutes; pass JMH options with `-Pjmh="..."`). Results are recorded in `docs/perf/`.

The project is organised as `common`, `fabric` and `neoforge` modules, with loader-specific hooks behind a ServiceLoader-based platform interface.

## License

Apache-2.0. See [LICENSE](LICENSE).
