// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tore.fluidphysics.client.render.FluidDensityField;
import dev.tore.fluidphysics.client.render.ParticleBatchRenderer;
import dev.tore.fluidphysics.command.StatsReport;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.RollingSamples;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;

/**
 * The client's own command root {@code /fluidphysics_client}, for what the server's {@code /fluidphysics} can not see
 * or change:
 * <ul>
 *   <li>{@code stats}: the statistics of this client's own simulation and renderer.</li>
 *   <li>{@code rendermode}: prints the render mode; {@code rendermode smooth|cubes} sets it and saves it in
 *       {@code config/fluidphysics-client.properties} (Plan.md §8.3, see {@link RenderMode} and
 *       {@link ClientConfig}).</li>
 * </ul>
 * Needs no permission: it only reads local numbers and changes local settings.
 *
 * <p>A root of its own (Plan.md §8.4.3): Fabric reports a client command that shares its root with a server command
 * but lacks the subcommand as an error instead of sending it to the server. The command source type differs per
 * loader (Fabric's {@code FabricClientCommandSource}, NeoForge's {@code CommandSourceStack}), so the loaders pass a
 * feedback function.
 *
 * <p>Client classes: only the loaders' client entry points may reference this class. Runs on the render thread.
 */
public final class ClientStatsCommand {
    public static final String NAME = "fluidphysics_client";

    private ClientStatsCommand() {
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, BiConsumer<S, Component> feedback) {
        LiteralArgumentBuilder<S> renderMode = LiteralArgumentBuilder.<S>literal("rendermode").executes(ctx -> {
            feedback.accept(ctx.getSource(), Component.literal("Fluid render mode: "
                    + ClientConfig.renderMode().id() + " (set with /" + NAME + " rendermode " + modeChoices() + ")"));
            return 1;
        });
        for (RenderMode mode : RenderMode.values()) {
            renderMode.then(LiteralArgumentBuilder.<S>literal(mode.id()).executes(ctx -> {
                String message = ClientConfig.setRenderMode(mode)
                        ? "Fluid render mode set to " + mode.id()
                        : "Fluid render mode is already " + mode.id();
                feedback.accept(ctx.getSource(), Component.literal(message));
                return 1;
            }));
        }
        dispatcher.register(LiteralArgumentBuilder.<S>literal(NAME)
                .then(LiteralArgumentBuilder.<S>literal("stats").executes(ctx -> {
                    feedback.accept(ctx.getSource(), Component.literal(report()));
                    return 1;
                }))
                .then(renderMode));
    }

    /** The mode names separated by {@code |}, as typed after {@code rendermode}. */
    private static String modeChoices() {
        StringBuilder choices = new StringBuilder();
        for (RenderMode mode : RenderMode.values()) {
            choices.append(choices.isEmpty() ? "" : "|").append(mode.id());
        }
        return choices.toString();
    }

    private static String report() {
        FluidSimulation simulation = ClientSimulation.current();
        if (simulation == null || simulation.activeCount() == 0) {
            return "No client particles (limit " + ClientSimulation.DEFAULT_LIMIT + ")";
        }
        return "Client fluid physics: " + simulation.activeCount() + " particles (" + simulation.countResting()
                + " resting), limit " + simulation.limit() + ", " + simulation.capacity() + " allocated\n"
                + StatsReport.simulationLines(simulation) + "\n"
                + renderLines(simulation.activeCount());
    }

    /** The render line, plus a line about the surface in the smooth mode. */
    private static String renderLines(int particles) {
        RollingSamples frame = ParticleBatchRenderer.frameNanos();
        RenderMode mode = ParticleBatchRenderer.lastMode();
        if (frame.count() == 0 || mode == null) {
            return "  render (" + ClientConfig.renderMode().id() + "): no frames drawn yet";
        }
        String allocated = AllocationMeter.isSupported()
                ? StatsReport.bytes(ParticleBatchRenderer.frameAllocated().mean()) + " (max "
                + StatsReport.bytes(ParticleBatchRenderer.frameAllocated().max()) + ")"
                : "unknown";
        boolean smooth = mode == RenderMode.SMOOTH;
        String text = "  render (" + mode.id() + "): " + StatsReport.millis(frame.last()) + " ms CPU per frame (mean "
                + StatsReport.millis(frame.mean()) + ", p99 " + StatsReport.millis(frame.percentile(0.99))
                + ", max " + StatsReport.millis(frame.max()) + ") over the last " + frame.count()
                + " frames, of which meshing " + StatsReport.millis(ParticleBatchRenderer.meshNanos().mean())
                + " ms; drew " + ParticleBatchRenderer.lastDrawn() + " of " + particles + " particles"
                + (smooth ? " as cubes, " : " with ") + ParticleBatchRenderer.lastVertices()
                + (smooth ? " vertices with the surface" : " vertices") + "; allocated per frame " + allocated;
        if (!smooth) {
            return text;
        }
        return text + "\n  smooth surface: " + ParticleBatchRenderer.lastTiles() + " field tiles ("
                + ParticleBatchRenderer.lastMeshedTiles() + " meshed), " + ParticleBatchRenderer.lastTriangles()
                + " triangles, " + ParticleBatchRenderer.lastFallback()
                + " particles drawn as cubes because the field was full (" + FluidDensityField.MAX_TILES
                + " tiles per fluid); mean per frame: field "
                + StatsReport.millis(ParticleBatchRenderer.fieldNanos().mean())
                + " ms, meshing and sorting " + StatsReport.millis(ParticleBatchRenderer.surfaceNanos().mean())
                + " ms, shading " + StatsReport.millis(ParticleBatchRenderer.shadeNanos().mean()) + " ms";
    }
}
