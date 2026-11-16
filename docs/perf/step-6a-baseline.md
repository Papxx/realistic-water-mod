# Step 6a baseline: solver performance before Step 6b

Measured on 2026-11-16 with `SolverStepBenchmark` (JMH 1.37), before any Step 6b optimisation. Use these numbers as the "before" of every Step 6 change (Plan.md §6: every optimisation needs a before/after number).

## Setup

- AMD Ryzen 5 5600H: 6 cores, 12 threads; 15 GB RAM; CachyOS Linux; OpenJDK 21.0.13; JMH fork with `-Xms1g -Xmx1g`.
- A normal desktop session was running (VS Code with Java language servers, a second Gradle daemon, KWin). Single-threaded runs varied by about 1 %. Runs with the 6-thread pool had occasional outlier iterations, 2 to 5 times slower, when other processes took a core: the solver's passes wait for their slowest worker.
- **Ticks:** one `PbfSolver.step()`, which is 2 substeps with 2 solver iterations each. Each invocation restores the same snapshot and runs 40 ticks.
- **damBreak:** a water column on an 8 x 8 block footprint (1, 4 or 8 blocks high) in a 16 x 16 box, measured from 150 ticks after release, while the water is still moving. At 16k this is the scene of `PbfSolverTest`.
- **restingPool:** a pool 1, 4 or 8 blocks deep in an 8 x 8 box, after it settled for 800 ticks.
- **threads:** 1 means the calling thread only. 6 is `WorkerPool.defaultSize()` on this machine (half the logical CPUs).

Reproduce with:

```bash
./gradlew :common:1.21.11:jmh                      # everything, about 5.5 minutes
./gradlew :common:1.21.11:jmh -Pjmh="-p particles=16384 -p threads=0 -wi 5 -i 8"
```

Close other heavy programs first. Compare variants within one run, or run them back to back on an idle machine (check `uptime`).

## Results: ms per tick

The table shows the median over the measured iterations. With 6 threads it combines the two runs, 13 iterations in total. The JMH mean with its 99.9 % error is in the raw files [`step-6a-baseline.json`](step-6a-baseline.json) and [`step-6a-baseline-multithread-rerun.json`](step-6a-baseline-multithread-rerun.json). Outliers make those means misleading for the 6-thread rows, for example 16k damBreak: 7.5 ± 11.2 ms.

| Particles | Scenario    | 1 thread | 6 threads | Speed-up |
|----------:|-------------|---------:|----------:|---------:|
|     4,096 | damBreak    |     3.25 |      0.81 |     4.0x |
|     4,096 | restingPool |     4.54 |      1.08 |     4.2x |
|    16,384 | damBreak    |    18.07 |      4.70 |     3.8x |
|    16,384 | restingPool |    19.20 |      4.52 |     4.2x |
|    32,768 | damBreak    |    44.01 |     10.1  |     4.4x |
|    32,768 | restingPool |    41.83 |      9.9  |     4.2x |

The Plan.md target is **3.5 ms at 16,384 particles**. The baseline is 4.5 to 4.7 ms with 6 threads, so Step 6b has to save about a quarter. A server tick has 50 ms for everything, so 32k particles at 10 ms would take a fifth of it.

## Where the time goes

These are the solver's own phase timings (`PbfSolver.lastPhaseNanos`), mean ms per tick over the measured ticks. Each phase is summed over both substeps.

| Phase      | What it covers                                       | 16k damBreak, 1 thread | share | 16k damBreak, 6 threads | 32k restingPool, 6 threads |
|------------|------------------------------------------------------|-----------------------:|------:|------------------------:|---------------------------:|
| predict    | gravity, speed limit, predicted positions            |                  0.08 |   0 % |                    0.03 |                       0.07 |
| grid       | cells, counting sort, copy into grid order           |                  0.59 |   3 % |                    0.40 |                       0.87 |
| neighbours | neighbour lists (27 cells, at most 32 neighbours)    |                  5.91 |  33 % |                    1.47 |                       3.21 |
| collision  | world collision, wall density, applying corrections  |                  2.63 |  15 % |                    0.99 |                       1.22 |
| density    | lambdas and position corrections, 2 iterations       |                  7.43 |  41 % |                    1.79 |                       3.89 |
| velocity   | velocities, bounce, XSPH viscosity                   |                  1.13 |   6 % |                    0.28 |                       0.67 |
| write-back | copy back into particle order, sleep counters        |                  0.31 |   2 % |                    0.15 |                       0.25 |

Mean neighbours per particle: 5.6 (4k damBreak, a thin layer), 10.4 (16k damBreak), 12.0 to 12.2 (resting pools).

## What this means for Step 6b to 6d

1. **Neighbour lists and the density solve are three quarters of the work.** The lists are built once per substep, so twice per tick. Reusing them for the second substep (Plan.md §6.6) would save up to half of the neighbours phase, about 0.7 ms of 4.7 ms at 16k with 6 threads, if the particles move little enough within a tick (a skin radius would make that safe).
2. **A settled pool costs as much as moving water**: 19.2 ms against 18.1 ms on one thread at 16k. The solver does full work for sleeping particles. Sleeping islands (§6.1.7) have the most potential for settled water, which is the common case in a world, but they do not help the moving damBreak scene that `PbfSolverTest` measures.
3. **The grid has a fixed cost.** Clearing and prefix-summing the 65,536 buckets takes about 0.11 to 0.16 ms per tick even at 4k particles, the same with 1 and 6 threads, which is about 14 % of a 4k tick with 6 threads. A compact list of occupied cells (§6.1.2) would remove it. At 32k the grid phase does not scale either (0.87 to 1.15 ms with 6 threads against 1.4 to 1.7 ms on one).
4. **Multi-threaded ticks are sensitive to other load.** With 6 workers on 6 cores, one busy core stalls every pass. In the game the server thread, the render thread and the solver share the CPU. The time budget (§6.1.8) and a smaller default pool on the integrated server deserve a look.
5. Superlinear scaling on one thread (4k to 16k: 5.6x for 4x the particles) comes mostly from the neighbour count, which grows from 5.6 to 10.4 per particle as the column gets deeper.

## In-game statistics

`/fluidphysics stats` (server) and `/fluidphysics_client stats` (client) show the same breakdown live over the last 200 ticks, with the tick's other parts (voxel cache, the server's interactions), p99 and maximum, heap bytes allocated per tick (ticking thread and solver threads) and, on the server, packets and bytes sent per second. On the client the command also shows the render time per frame. Use them for real-world before/after numbers. The JMH numbers above isolate the solver.
