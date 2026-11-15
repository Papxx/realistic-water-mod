// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.interaction.CauldronFilling;
import dev.tore.fluidphysics.interaction.Condensation;
import dev.tore.fluidphysics.interaction.FluidReactions;
import dev.tore.fluidphysics.interaction.StoneFormation;
import dev.tore.fluidphysics.item.PhysicsBucketItem;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * The world interactions of one server level's particles: water and lava reactions, cauldron filling and
 * condensation back into vanilla blocks. Decides on the server only; particles are removed through
 * {@link ServerSimulations#removeMarked}, which tells the clients, and blocks change through the level as usual.
 *
 * <p>Server thread only; {@link ServerSimulations#tick} calls {@link #tick} after each simulation step.
 */
final class ServerInteractions {
    /** Reactions and cauldrons run every this many ticks. */
    static final int CONTACT_INTERVAL = 2;
    /** Condensation, stalled stone and stale cauldrons are checked every this many ticks (once a second). */
    static final int CONDENSE_INTERVAL = 20;
    /** Most lava fizz effects per run, so a big reaction does not flood the clients with sounds. */
    private static final int MAX_FIZZ_PER_RUN = 4;

    private final ServerLevel level;
    private final FluidReactions reactions = new FluidReactions();
    private final StoneFormation stone = new StoneFormation();
    private final CauldronFilling cauldrons = new CauldronFilling();
    private final Condensation condensation = new Condensation();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private long ticks;
    private long now;
    private int fizzLeft;

    private final StoneFormation.Placer stonePlacer = this::placeStone;
    private final FluidReactions.Sink reactionSink = new FluidReactions.Sink() {
        @Override
        public void cooled(int x, int y, int z) {
            stone.add(x, y, z, now, stonePlacer);
        }

        @Override
        public void quenched(int x, int y, int z, boolean source) {
            pos.set(x, y, z);
            level.setBlockAndUpdate(pos, (source ? Blocks.OBSIDIAN : Blocks.COBBLESTONE).defaultBlockState());
            fizz(pos);
        }
    };
    private final CauldronFilling.World cauldronWorld = new CauldronFilling.World() {
        @Override
        public int fillState(long key) {
            BlockState state = level.getBlockState(pos.set(key));
            if (state.is(Blocks.CAULDRON)) {
                return CauldronFilling.EMPTY;
            }
            if (state.is(Blocks.WATER_CAULDRON)) {
                return state.getValue(LayeredCauldronBlock.LEVEL);
            }
            if (state.is(Blocks.LAVA_CAULDRON)) {
                return CauldronFilling.LAVA;
            }
            return state.getBlock() instanceof AbstractCauldronBlock
                    ? CauldronFilling.OTHER : CauldronFilling.NOT_A_CAULDRON;
        }

        @Override
        public void setFillState(long key, int state) {
            pos.set(key);
            if (state == CauldronFilling.LAVA) {
                level.setBlockAndUpdate(pos, Blocks.LAVA_CAULDRON.defaultBlockState());
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1f, 1f);
            } else {
                level.setBlockAndUpdate(pos,
                        Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, state));
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6f, 1.2f);
            }
        }

        @Override
        public void giveBack(long key, byte material, int count) {
            pos.set(key);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                pos.move(0, 1, 0);
            }
            ServerSimulations.spawn(level, pos.getX(), pos.getY(), pos.getZ(), material, count, 0f, 0f, 0f);
        }
    };
    private final Condensation.World condenseWorld = new Condensation.World() {
        @Override
        public boolean canPlace(long key, byte material) {
            pos.set(key);
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            if (!state.canBeReplaced()) {
                return false;
            }
            FluidState fluid = state.getFluidState();
            if (material == ParticleSoA.MATERIAL_WATER || material == ParticleSoA.MATERIAL_LAVA) {
                // Plants and other replaceable blocks, or flowing fluid of the same kind; never another fluid.
                return fluid.isEmpty() || !fluid.isSource() && fluid.getType().isSame(fluidOf(material));
            }
            // Sand and slime blocks are solid: keep them out of living entities.
            return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty();
        }

        @Override
        public boolean isOwnSource(long key, byte material) {
            FluidState fluid = level.getFluidState(pos.set(key));
            return fluid.isSource() && fluid.getType().isSame(fluidOf(material));
        }

        @Override
        public void place(long key, byte material) {
            pos.set(key);
            if (material == ParticleSoA.MATERIAL_WATER && PhysicsBucketItem.waterEvaporates(level, pos)) {
                fizz(pos);
                return;
            }
            BlockState current = level.getBlockState(pos);
            if (!current.isAir() && current.getFluidState().isEmpty()) {
                // Like a vanilla bucket: replaceable plants drop as items.
                level.destroyBlock(pos, true);
            }
            BlockState state = switch (material) {
                case ParticleSoA.MATERIAL_WATER -> Blocks.WATER.defaultBlockState();
                case ParticleSoA.MATERIAL_LAVA -> Blocks.LAVA.defaultBlockState();
                case ParticleSoA.MATERIAL_SLIME -> Blocks.SLIME_BLOCK.defaultBlockState();
                default -> Blocks.SAND.defaultBlockState();
            };
            level.setBlockAndUpdate(pos, state);
        }
    };

    ServerInteractions(ServerLevel level) {
        this.level = level;
    }

    /** Runs the interactions that are due this tick. */
    void tick(FluidSimulation simulation) {
        ticks++;
        now = ticks;
        fizzLeft = MAX_FIZZ_PER_RUN;
        boolean particles = simulation.activeCount() > 0;
        if (particles && ticks % CONTACT_INTERVAL == 0) {
            reactions.run(simulation, reactionSink);
            cauldrons.run(simulation, cauldronWorld);
            ServerSimulations.removeMarked(level, simulation);
        }
        if (ticks % CONDENSE_INTERVAL == 0) {
            stone.finishStalled(ticks, stonePlacer);
            cauldrons.returnStale(cauldronWorld);
            if (simulation.activeCount() > 0) {
                condensation.run(simulation, condenseWorld);
                ServerSimulations.removeMarked(level, simulation);
            }
        }
    }

    /** Forgets stone that was still forming (after {@code /fluidphysics clear}). */
    void clear() {
        stone.clear();
    }

    int stoneForming() {
        return stone.size();
    }

    private boolean placeStone(int x, int y, int z, boolean obsidian) {
        BlockState block = (obsidian ? Blocks.OBSIDIAN : Blocks.COBBLESTONE).defaultBlockState();
        // In the block itself, or on top of it if something solid sits there now.
        for (int dy = 0; dy <= 1; dy++) {
            pos.set(x, y + dy, z);
            if (level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced()) {
                level.setBlockAndUpdate(pos, block);
                fizz(pos);
                return true;
            }
        }
        return false;
    }

    private void fizz(BlockPos at) {
        if (fizzLeft > 0) {
            fizzLeft--;
            level.levelEvent(LevelEvent.LAVA_FIZZ, at, 0);
        }
    }

    private static net.minecraft.world.level.material.Fluid fluidOf(byte material) {
        return material == ParticleSoA.MATERIAL_LAVA ? Fluids.LAVA : Fluids.WATER;
    }
}
