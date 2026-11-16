// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tore.fluidphysics.client.render.ParticleBatchRenderer;
import dev.tore.fluidphysics.command.StatsReport;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.RollingSamples;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;

/**
 * {@code /fluidphysics_client stats}: the statistics of this client's own simulation and renderer, which the server's
 * {@code /fluidphysics stats} can not see. Needs no permission; it only reads local numbers.
 *
 * <p>A root of its own: Fabric reports a client command that shares its root with a server command but lacks the
 * subcommand as an error instead of sending it to the server. The command source type differs per loader (Fabric's
 * {@code FabricClientCommandSource}, NeoForge's {@code CommandSourceStack}), so the loaders pass a feedback function.
 *
 * <p>Client classes: only the loaders' client entry points may reference this class. Runs on the render thread.
 */
public final class ClientStatsCommand {
    public static final String NAME = "fluidphysics_client";

    private ClientStatsCommand() {
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, BiConsumer<S, Component> feedback) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal(NAME)
                .then(LiteralArgumentBuilder.<S>literal("stats").executes(ctx -> {
                    feedback.accept(ctx.getSource(), Component.literal(report()));
                    return 1;
                })));
    }

    private static String report() {
        FluidSimulation simulation = ClientSimulation.current();
        if (simulation == null || simulation.activeCount() == 0) {
            return "No client particles (limit " + ClientSimulation.DEFAULT_LIMIT + ")";
        }
        return "Client fluid physics: " + simulation.activeCount() + " particles (" + simulation.countResting()
                + " resting), limit " + simulation.limit() + ", " + simulation.capacity() + " allocated\n"
                + StatsReport.simulationLines(simulation) + "\n"
                + renderLine(simulation.activeCount());
    }

    private static String renderLine(int particles) {
        RollingSamples frame = ParticleBatchRenderer.frameNanos();
        if (frame.count() == 0) {
            return "  render: no frames drawn yet";
        }
        String allocated = AllocationMeter.isSupported()
                ? StatsReport.bytes(ParticleBatchRenderer.frameAllocated().mean()) + " (max "
                + StatsReport.bytes(ParticleBatchRenderer.frameAllocated().max()) + ")"
                : "unknown";
        return "  render: " + StatsReport.millis(frame.last()) + " ms CPU per frame (mean "
                + StatsReport.millis(frame.mean()) + ", p99 " + StatsReport.millis(frame.percentile(0.99))
                + ", max " + StatsReport.millis(frame.max()) + ") over the last " + frame.count()
                + " frames, of which meshing " + StatsReport.millis(ParticleBatchRenderer.meshNanos().mean())
                + " ms; drew " + ParticleBatchRenderer.lastDrawn() + " of " + particles + " particles with "
                + ParticleBatchRenderer.lastVertices() + " vertices; allocated per frame " + allocated;
    }
}
