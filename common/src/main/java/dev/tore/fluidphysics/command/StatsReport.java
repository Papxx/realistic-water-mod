// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.command;

import dev.tore.fluidphysics.network.NetworkStats;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.MaterialProperties;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.PbfSolver;
import dev.tore.fluidphysics.physics.RollingSamples;
import dev.tore.fluidphysics.physics.SimulationStats;
import java.util.Locale;

/**
 * Text of the statistics commands ({@code /fluidphysics stats} on the server, {@code /fluidphysics_client stats} on the
 * client): one line per topic, indented under a headline. Plain Java, so it can be tested without a game.
 */
public final class StatsReport {
    private StatsReport() {
    }

    /**
     * Particle counts, per-tick timings with their breakdown, neighbours, allocations and the voxel cache of
     * {@code simulation}, one line each without the headline. The simulation must hold particles.
     */
    public static String simulationLines(FluidSimulation simulation) {
        SimulationStats stats = simulation.stats();
        StringBuilder text = new StringBuilder();

        int[] counts = new int[ParticleSoA.MATERIAL_COUNT];
        simulation.countByMaterial(counts);
        text.append("  ");
        for (byte mat = 0; mat < ParticleSoA.MATERIAL_COUNT; mat++) {
            text.append(mat == 0 ? "" : ", ").append(MaterialProperties.name(mat)).append(' ').append(counts[mat]);
        }
        text.append('\n');

        RollingSamples tick = stats.tick();
        text.append("  tick ").append(millis(tick.last())).append(" ms (mean ").append(millis(tick.mean()))
                .append(", p99 ").append(millis(tick.percentile(0.99))).append(", max ").append(millis(tick.max()))
                .append(") over the last ").append(stats.samples()).append(" ticks; solver step mean ")
                .append(millis(stats.step().mean())).append(" ms, p99 ").append(millis(stats.step().percentile(0.99)))
                .append('\n');

        text.append("  mean per tick: voxel cache ").append(millis(stats.voxels().mean()));
        for (int phase = 0; phase < PbfSolver.PHASE_COUNT; phase++) {
            text.append(", ").append(PbfSolver.phaseName(phase)).append(' ')
                    .append(millis(stats.phase(phase).mean()));
        }
        if (stats.interactions().max() > 0L) {
            text.append(", interactions ").append(millis(stats.interactions().mean()));
        }
        text.append(" ms\n");

        text.append("  neighbours: ").append(String.format(Locale.ROOT, "%.1f", stats.lastMeanNeighbors()))
                .append(" per particle, up to ").append(stats.lastMaxNeighbors()).append(" (cap ")
                .append(PbfSolver.MAX_NEIGHBORS).append("); ").append(simulation.workerThreads())
                .append(" solver threads\n");

        if (AllocationMeter.isSupported()) {
            text.append("  allocated per tick: ").append(bytes(stats.allocated().mean())).append(" (max ")
                    .append(bytes(stats.allocated().max())).append(") on the ticking thread, ")
                    .append(bytes(stats.workerAllocated().mean())).append(" (max ")
                    .append(bytes(stats.workerAllocated().max())).append(") on the solver threads\n");
        } else {
            text.append("  allocated per tick: unknown (this JVM has no per-thread allocation counters)\n");
        }

        text.append("  voxel cache: ").append(simulation.voxels().size()).append(" solid blocks in ")
                .append(simulation.voxels().cachedSectionCount()).append(" sections; ")
                .append(simulation.removedInVoid()).append(" particles fell out of the world");
        return text.toString();
    }

    /** One line about the packets the server sent recently, indented like {@link #simulationLines}. */
    public static String networkLine(NetworkStats sent) {
        StringBuilder text = new StringBuilder("  network, last ").append(NetworkStats.WINDOW_SECONDS)
                .append(" s: ");
        double total = 0;
        StringBuilder kinds = new StringBuilder();
        for (int kind = 0; kind < NetworkStats.KIND_COUNT; kind++) {
            double perSecond = sent.packetsPerSecond(kind);
            total += perSecond;
            kinds.append(kind == 0 ? "" : ", ").append(NetworkStats.kindName(kind)).append(' ')
                    .append(String.format(Locale.ROOT, "%.1f", perSecond));
        }
        long totalPackets = 0L;
        for (int kind = 0; kind < NetworkStats.KIND_COUNT; kind++) {
            totalPackets += sent.totalPackets(kind);
        }
        text.append(String.format(Locale.ROOT, "%.1f", total)).append(" packets/s (").append(kinds)
                .append("), about ").append(bytes(Math.round(sent.bytesPerSecond()))).append("/s, peak ")
                .append(sent.peakPacketsPerSecond()).append(" packets in one second; ").append(totalPackets)
                .append(" packets (about ").append(bytes(sent.totalBytes())).append(") since the server started");
        return text.toString();
    }

    /** Nanoseconds as milliseconds with two decimals. */
    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1.0e6);
    }

    /** A byte count with a binary unit: {@code 512 B}, {@code 1.5 KiB}, {@code 2.0 MiB}. */
    public static String bytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
