// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.registry;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.block.PhysicsSandBlock;
import dev.tore.fluidphysics.item.PhysicsBucketItem;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ParticleTickets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The mod's blocks and items. Common code creates them; each loader registers them in its own way by passing a
 * registrar to {@link #registerBlocks} and {@link #registerItems} (blocks first), and adds them to the creative tabs
 * through {@link #addToCreativeTab}. On 1.21.11 the loaders also register the chunk ticket type
 * ({@code registerTicketTypes}).
 */
public final class ModRegistry {
    public static final Identifier PHYSICS_SAND_ID = FluidPhysicsCommon.id("physics_sand");
    public static final Identifier WATER_BUCKET_ID = FluidPhysicsCommon.id("physics_water_bucket");
    public static final Identifier LAVA_BUCKET_ID = FluidPhysicsCommon.id("physics_lava_bucket");
    public static final Identifier SLIME_BUCKET_ID = FluidPhysicsCommon.id("physics_slime_bucket");

    /*
     * Vanilla creative tabs. The constants in CreativeModeTabs are private, so build the keys from their ids.
     */
    public static final ResourceKey<CreativeModeTab> TOOLS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
    public static final ResourceKey<CreativeModeTab> NATURAL_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("natural_blocks"));

    private static Block physicsSand;
    private static Item physicsSandItem;
    private static PhysicsBucketItem waterBucket;
    private static PhysicsBucketItem lavaBucket;
    private static PhysicsBucketItem slimeBucket;

    private ModRegistry() {
    }

    /**
     * Item properties for the item that will be registered as {@code id}.
     * Since 1.21.2 the id must be set before the properties reach an {@link Item} constructor.
     */
    public static Item.Properties itemProps(Identifier id) {
        Item.Properties props = new Item.Properties();
        //? if >=1.21.2 {
        props.setId(ResourceKey.create(Registries.ITEM, id));
        //?}
        return props;
    }

    /**
     * Block properties for the block that will be registered as {@code id}, starting from {@code base}.
     * Since 1.21.2 the id must be set before the properties reach a block constructor.
     */
    public static BlockBehaviour.Properties blockProps(Identifier id, BlockBehaviour.Properties base) {
        //? if >=1.21.2 {
        base.setId(ResourceKey.create(Registries.BLOCK, id));
        //?}
        return base;
    }

    /** Item properties for the block item of the block registered as {@code id}. */
    private static Item.Properties blockItemProps(Identifier id) {
        Item.Properties props = itemProps(id);
        //? if >=1.21.2 {
        // Since 1.21.2 a block item takes its name from the item id unless told to use the block's.
        props.useBlockDescriptionPrefix();
        //?}
        return props;
    }

    /** Creates the blocks and hands each to {@code registrar}. Call once, before {@link #registerItems}. */
    public static void registerBlocks(BiConsumer<Identifier, Block> registrar) {
        physicsSand = new PhysicsSandBlock(
                blockProps(PHYSICS_SAND_ID, BlockBehaviour.Properties.ofFullCopy(Blocks.SAND).noLootTable()));
        registrar.accept(PHYSICS_SAND_ID, physicsSand);
    }

    /** Creates the items and hands each to {@code registrar}. Call once, after {@link #registerBlocks}. */
    public static void registerItems(BiConsumer<Identifier, Item> registrar) {
        physicsSandItem = new BlockItem(physicsSand, blockItemProps(PHYSICS_SAND_ID));
        waterBucket = bucket(WATER_BUCKET_ID, ParticleSoA.MATERIAL_WATER);
        lavaBucket = bucket(LAVA_BUCKET_ID, ParticleSoA.MATERIAL_LAVA);
        slimeBucket = bucket(SLIME_BUCKET_ID, ParticleSoA.MATERIAL_SLIME);
        registrar.accept(PHYSICS_SAND_ID, physicsSandItem);
        registrar.accept(WATER_BUCKET_ID, waterBucket);
        registrar.accept(LAVA_BUCKET_ID, lavaBucket);
        registrar.accept(SLIME_BUCKET_ID, slimeBucket);
    }

    //? if >=1.21.11 {
    /**
     * Hands the mod's chunk ticket types to {@code registrar}. Only 1.21.11 has a ticket type registry; 1.21.1 creates
     * ticket types without registering them.
     */
    public static void registerTicketTypes(BiConsumer<Identifier, net.minecraft.server.level.TicketType> registrar) {
        registrar.accept(ParticleTickets.ID, ParticleTickets.TYPE);
    }
    //?}

    private static PhysicsBucketItem bucket(Identifier id, byte material) {
        return new PhysicsBucketItem(material, itemProps(id).stacksTo(1));
    }

    /** Hands the entries of creative tab {@code tab} to {@code output}, if the mod adds any to it. */
    public static void addToCreativeTab(ResourceKey<CreativeModeTab> tab, Consumer<ItemStack> output) {
        if (tab.equals(TOOLS_TAB)) {
            output.accept(new ItemStack(waterBucket));
            output.accept(new ItemStack(lavaBucket));
            output.accept(new ItemStack(slimeBucket));
        } else if (tab.equals(NATURAL_TAB)) {
            output.accept(new ItemStack(physicsSandItem));
        }
    }

    public static Block physicsSand() {
        return physicsSand;
    }

    /** The bucket that holds {@code material}, or null for sand (which has no bucket). */
    public static PhysicsBucketItem bucketFor(byte material) {
        return switch (material) {
            case ParticleSoA.MATERIAL_WATER -> waterBucket;
            case ParticleSoA.MATERIAL_LAVA -> lavaBucket;
            case ParticleSoA.MATERIAL_SLIME -> slimeBucket;
            default -> null;
        };
    }
}
