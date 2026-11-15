// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.MaterialProperties;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ServerSimulations;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /fluidphysics stats | clear | spawn <material> [blocks] [pos] | limit [max]}, for operators (permission level 2,
 * like {@code /tick}). All subcommands act on the level of the command source.
 */
public final class FluidPhysicsCommand {
    /** Most blocks one spawn fills: 512 blocks of 64 particles are the hard cap of 32,768 particles. */
    public static final int MAX_SPAWN_BLOCKS = ParticleSoA.MAX_PARTICLES / ParticleSoA.PARTICLES_PER_BLOCK;

    // 1.21.11 replaced the integer permission levels with permission sets.
    //? if >=1.21.11 {
    private static final Predicate<CommandSourceStack> GAME_MASTER = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    //?} else {
    /*private static final Predicate<CommandSourceStack> GAME_MASTER =
            source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    *///?}

    private FluidPhysicsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> spawn = Commands.literal("spawn");
        for (byte material = 0; material < ParticleSoA.MATERIAL_COUNT; material++) {
            byte mat = material;
            spawn.then(Commands.literal(MaterialProperties.name(mat))
                    .executes(ctx -> spawn(ctx.getSource(), mat, 1, sourceBlock(ctx.getSource())))
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1, MAX_SPAWN_BLOCKS))
                            .executes(ctx -> spawn(ctx.getSource(), mat, IntegerArgumentType.getInteger(ctx, "blocks"),
                                    sourceBlock(ctx.getSource())))
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(ctx -> spawn(ctx.getSource(), mat,
                                            IntegerArgumentType.getInteger(ctx, "blocks"),
                                            BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))));
        }
        dispatcher.register(Commands.literal("fluidphysics")
                .requires(GAME_MASTER)
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
                .then(spawn)
                .then(Commands.literal("limit")
                        .executes(ctx -> showLimit(ctx.getSource()))
                        .then(Commands.argument("max", IntegerArgumentType.integer(0, ParticleSoA.MAX_PARTICLES))
                                .executes(ctx -> setLimit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "max"))))));
    }

    private static BlockPos sourceBlock(CommandSourceStack source) {
        return BlockPos.containing(source.getPosition());
    }

    /**
     * Fills {@code blocks} blocks with 64 particles each: a cube centred on {@code start} horizontally that grows upward
     * from it. Blocks with a collision shape and unloaded blocks are skipped.
     */
    private static int spawn(CommandSourceStack source, byte material, int blocks, BlockPos start) {
        ServerLevel level = source.getLevel();
        FluidSimulation simulation = ServerSimulations.get(level);
        if (!simulation.canSpawnAt(start.getX(), start.getY(), start.getZ())) {
            source.sendFailure(Component.literal("Too far from the existing particles (more than "
                    + FluidSimulation.MAX_ORIGIN_DISTANCE + " blocks); run /fluidphysics clear first"));
            return 0;
        }
        int edge = 1;
        while (edge * edge * edge < blocks) {
            edge++;
        }
        int x0 = start.getX() - (edge - 1) / 2;
        int z0 = start.getZ() - (edge - 1) / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int visited = 0;
        int filled = 0;
        int particles = 0;
        int skipped = 0;
        fill:
        for (int dy = 0; dy < edge; dy++) {
            for (int dz = 0; dz < edge; dz++) {
                for (int dx = 0; dx < edge; dx++) {
                    if (visited++ == blocks) {
                        break fill;
                    }
                    pos.set(x0 + dx, start.getY() + dy, z0 + dz);
                    // isLoaded is false outside the build height too; getBlockState would load the chunk otherwise.
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                        skipped++;
                        continue;
                    }
                    int n = ServerSimulations.spawn(level, pos.getX(), pos.getY(), pos.getZ(), material,
                            ParticleSoA.PARTICLES_PER_BLOCK, 0f, 0f, 0f);
                    particles += n;
                    if (n > 0) {
                        filled++;
                    }
                    if (n < ParticleSoA.PARTICLES_PER_BLOCK) {
                        break fill;
                    }
                }
            }
        }
        boolean limitReached = simulation.activeCount() >= simulation.limit();
        if (particles == 0) {
            source.sendFailure(Component.literal(limitReached
                    ? "Particle limit reached (" + simulation.limit() + " in this dimension)"
                    : "No free space at " + start.toShortString()));
            return 0;
        }
        String message = "Spawned " + particles + " " + MaterialProperties.name(material) + " particles in " + filled
                + " blocks at " + start.toShortString()
                + (skipped > 0 ? ", skipped " + skipped + " solid or unloaded blocks" : "")
                + (limitReached ? ", particle limit " + simulation.limit() + " reached" : "");
        source.sendSuccess(() -> Component.literal(message), true);
        return particles;
    }

    private static int stats(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        FluidSimulation simulation = ServerSimulations.getIfPresent(level);
        String dimension = dimensionName(level);
        int total = ServerSimulations.totalParticles();
        if (simulation == null || simulation.activeCount() == 0) {
            source.sendSuccess(() -> Component.literal("No particles in " + dimension + " (" + total
                    + " in all dimensions, limit " + ServerSimulations.limit() + " per dimension)"), false);
            return 0;
        }
        int[] counts = new int[ParticleSoA.MATERIAL_COUNT];
        simulation.countByMaterial(counts);
        StringBuilder materials = new StringBuilder();
        for (byte mat = 0; mat < ParticleSoA.MATERIAL_COUNT; mat++) {
            materials.append(mat == 0 ? "" : ", ").append(MaterialProperties.name(mat)).append(' ').append(counts[mat]);
        }
        int active = simulation.activeCount();
        String message = "Fluid physics in " + dimension + ": " + active + " particles ("
                + simulation.countResting() + " resting), limit " + simulation.limit() + ", "
                + simulation.capacity() + " allocated\n"
                + "  " + materials + "\n"
                + "  step " + millis(simulation.lastStepNanos()) + " ms, average "
                + millis(simulation.averageStepNanos()) + " ms, max " + millis(simulation.maxStepNanos())
                + " ms over the last " + FluidSimulation.STATS_WINDOW + " ticks; up to "
                + simulation.lastMaxNeighbors() + " neighbours, " + simulation.workerThreads() + " solver threads\n"
                + "  voxel cache: " + simulation.voxels().size() + " solid blocks in "
                + simulation.voxels().cachedSectionCount() + " sections; " + simulation.removedInVoid()
                + " particles fell out of the world\n"
                + "  all dimensions: " + total + " particles";
        source.sendSuccess(() -> Component.literal(message), false);
        return active;
    }

    private static int clear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int removed = ServerSimulations.clear(level);
        source.sendSuccess(() -> Component.literal("Removed " + removed + " particles in " + dimensionName(level)),
                true);
        return removed;
    }

    private static int showLimit(CommandSourceStack source) {
        int limit = ServerSimulations.limit();
        source.sendSuccess(() -> Component.literal("Particle limit: " + limit + " per dimension (at most "
                + ParticleSoA.MAX_PARTICLES + ")"), false);
        return limit;
    }

    private static int setLimit(CommandSourceStack source, int max) {
        ServerSimulations.setLimit(max);
        String message = "Particle limit set to " + max + " per dimension until the server stops"
                + (ServerSimulations.anyAboveLimit()
                ? "; particles above it stay until they condense or are cleared" : "");
        source.sendSuccess(() -> Component.literal(message), true);
        return max;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1.0e6);
    }

    private static String dimensionName(ServerLevel level) {
        //? if >=1.21.11 {
        return level.dimension().identifier().toString();
        //?} else {
        /*return level.dimension().location().toString();
        *///?}
    }
}
