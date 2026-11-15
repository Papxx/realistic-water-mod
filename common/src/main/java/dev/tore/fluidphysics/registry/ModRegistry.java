package dev.tore.fluidphysics.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModRegistry {
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
}
