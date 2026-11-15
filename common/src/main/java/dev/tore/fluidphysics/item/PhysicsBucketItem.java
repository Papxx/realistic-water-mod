// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.item;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.2 {
import net.minecraft.world.InteractionResult;
//?} else {
/*import net.minecraft.world.InteractionResultHolder;
*///?}

/**
 * A bucket of 64 water, lava or slime particles (Plan.md §4.1). Using it pours the particles into the block in front
 * of the player as a 4 x 4 x 4 grid, moving slightly in the look direction, and leaves an empty vanilla bucket. An
 * empty bucket picks 64 particles back up (see {@code BucketItemMixin}).
 *
 * <p>The server decides: if its simulation has no room for all 64 particles, nothing is poured, so a bucket never
 * loses part of its content.
 */
public class PhysicsBucketItem extends Item {
    /** Speed of the poured particles along the look direction, in blocks per second. */
    private static final float POUR_SPEED = 1.5f;

    private final byte material;

    public PhysicsBucketItem(byte material, Item.Properties properties) {
        super(properties);
        this.material = material;
    }

    /** The material id ({@code ParticleSoA.MATERIAL_*}) this bucket holds. */
    public byte material() {
        return material;
    }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack result = pour(level, player, stack);
        if (result == null) {
            return InteractionResult.PASS;
        }
        return result == stack ? InteractionResult.FAIL : InteractionResult.SUCCESS.heldItemTransformedTo(result);
    }
    //?} else {
    /*@Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack result = pour(level, player, stack);
        if (result == null) {
            return InteractionResultHolder.pass(stack);
        }
        return result == stack ? InteractionResultHolder.fail(stack)
                : InteractionResultHolder.sidedSuccess(result, level.isClientSide());
    }
    *///?}

    /**
     * Pours the bucket. Returns the stack the hand holds afterwards, {@code stack} itself if the use failed, or null if
     * no block is in reach.
     */
    private ItemStack pour(Level level, Player player, ItemStack stack) {
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos clicked = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(face);
        if (!level.mayInteract(player, clicked) || !player.mayUseItemAt(target, face, stack)) {
            return stack;
        }
        BlockState targetState = level.getBlockState(target);
        if (!targetState.isAir() && !targetState.canBeReplaced()) {
            return stack;
        }
        if (level instanceof ServerLevel server && !pourOnServer(server, player, target)) {
            return stack;
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return ItemUtils.createFilledResult(stack, player, BucketItem.getEmptySuccessItem(stack, player));
    }

    private boolean pourOnServer(ServerLevel level, Player player, BlockPos target) {
        if (material == ParticleSoA.MATERIAL_WATER && waterEvaporates(level, target)) {
            // Like a vanilla water bucket in the Nether: the water boils away.
            level.playSound(null, target, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5f,
                    2.6f + (level.random.nextFloat() - level.random.nextFloat()) * 0.8f);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, target.getX() + 0.5, target.getY() + 0.5,
                    target.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.0);
            return true;
        }
        FluidSimulation simulation = ServerSimulations.get(level);
        if (!simulation.canSpawnAt(target.getX(), target.getY(), target.getZ())) {
            player.displayClientMessage(Component.translatable("message.fluidphysics.too_far"), true);
            return false;
        }
        if (simulation.limit() - simulation.activeCount() < ParticleSoA.PARTICLES_PER_BLOCK) {
            player.displayClientMessage(Component.translatable("message.fluidphysics.limit_reached"), true);
            return false;
        }
        Vec3 look = player.getViewVector(1f);
        ServerSimulations.spawn(level, target.getX(), target.getY(), target.getZ(), material,
                ParticleSoA.PARTICLES_PER_BLOCK, (float) look.x * POUR_SPEED, (float) look.y * POUR_SPEED,
                (float) look.z * POUR_SPEED);
        level.playSound(null, target, emptySound(material), SoundSource.BLOCKS, 1f, 1f);
        level.gameEvent(player, GameEvent.FLUID_PLACE, target);
        return true;
    }

    /** Sound of pouring a bucket of {@code material}. */
    public static SoundEvent emptySound(byte material) {
        return switch (material) {
            case ParticleSoA.MATERIAL_LAVA -> SoundEvents.BUCKET_EMPTY_LAVA;
            case ParticleSoA.MATERIAL_SLIME -> SoundEvents.SLIME_BLOCK_PLACE;
            default -> SoundEvents.BUCKET_EMPTY;
        };
    }

    /** Sound of filling a bucket with {@code material}. */
    public static SoundEvent fillSound(byte material) {
        return switch (material) {
            case ParticleSoA.MATERIAL_LAVA -> SoundEvents.BUCKET_FILL_LAVA;
            case ParticleSoA.MATERIAL_SLIME -> SoundEvents.SLIME_BLOCK_BREAK;
            default -> SoundEvents.BUCKET_FILL;
        };
    }

    /** Whether water placed at {@code pos} boils away, as in the Nether. */
    public static boolean waterEvaporates(Level level, BlockPos pos) {
        //? if >=1.21.11 {
        return level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES,
                pos);
        //?} else {
        /*return level.dimensionType().ultraWarm();
        *///?}
    }
}
