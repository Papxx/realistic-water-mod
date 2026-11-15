# Realistic Fluid & Granular Physics

**Water that splashes. Lava that oozes. Slime that stretches. Sand that piles up.**

Realistic Fluid & Granular Physics (`fluidphysics`) replaces Minecraft's cellular fluids and falling-sand behaviour with a real-time, particle-based simulation. Liquids flow around obstacles, spray from pipes, burst through broken dams and settle into piles, all while staying fast enough for multiplayer.

> **Status:** early development (v0.1.0). The physics core (solver, spatial grid, voxel collision), the network events and the `/fluidphysics` command are in place and covered by unit tests. Particles can be spawned and simulated on servers, but they are not rendered yet: rendering, items and world hooks come next. The features below describe the design targets. See [Plan.md](Plan.md) for the detailed specification and progress.

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

- One bucket or one full block equals exactly **64 particles** (a 4×4×4 grid). Nothing is duplicated or lost.
- **Condensation:** when 64 particles of one material rest inside a single block space, they turn back into the matching vanilla block (water, lava, slime, sand). This keeps the particle count and lag low.
- **Cauldrons** physically fill up with particles and raise the vanilla cauldron level.
- Physics buckets spawn particles, and an empty bucket can scoop them up again.

### World interaction

- **Floodgates and dams:** breaking glass, opening trapdoors or retracting pistons wakes sleeping particles so they flow immediately.
- **Boats** use four-point buoyancy, so they pitch and roll with the water.
- **Anvils and TNT** displace fluid. Explosions push particles outward, and anvils bounce off slime.
- Chunk unloads force condensation, so no particles are orphaned and mass survives restarts.

### Smooth visuals

- Interpolated rendering at 60 to 240+ FPS on top of the 20 TPS simulation.
- Foam-coloured droplets, glowing lava, translucent slime and grainy sand.
- Rendering goes through standard render types for compatibility with Sodium and Iris.

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
/fluidphysics stats                         Show particle counts and solver timings
/fluidphysics clear                         Remove all particles
/fluidphysics spawn <material> [blocks] [pos]
                                            Fill blocks with particles (64 per block) at your position or pos
                                            material: water, lava, slime or sand; blocks: 1 to 512
/fluidphysics limit [n]                     Show or set the particle limit per dimension (0 to 32768)
```

## Building

```bash
./gradlew build                       # all four targets, including the unit tests
./gradlew :fabric:1.21.1:build        # one specific target
./gradlew :neoforge:1.21.11:build
./gradlew :common:1.21.11:test        # physics unit tests and the solver benchmark only
```

The solver benchmark (`PbfSolverTest`) prints the median time per tick at 16,384 particles and fails on any heap allocation inside the step loop.

The project is organised as `common`, `fabric` and `neoforge` modules, with loader-specific hooks behind a ServiceLoader-based platform interface.

## License

Apache-2.0. See [LICENSE](LICENSE).
