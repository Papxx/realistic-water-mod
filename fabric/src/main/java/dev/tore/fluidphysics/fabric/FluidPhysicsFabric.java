// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.fabric;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.command.FluidPhysicsCommand;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.network.FluidSyncPayload;
import dev.tore.fluidphysics.registry.ModRegistry;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class FluidPhysicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FluidPhysicsCommon.init();

        ModRegistry.registerBlocks((id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
        ModRegistry.registerItems((id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        //? if >=1.21.11 {
        ModRegistry.registerTicketTypes((id, type) -> Registry.register(BuiltInRegistries.TICKET_TYPE, id, type));
        //?}
        ItemGroupEvents.modifyEntriesEvent(ModRegistry.TOOLS_TAB)
                .register(entries -> ModRegistry.addToCreativeTab(ModRegistry.TOOLS_TAB, entries::accept));
        ItemGroupEvents.modifyEntriesEvent(ModRegistry.NATURAL_TAB)
                .register(entries -> ModRegistry.addToCreativeTab(ModRegistry.NATURAL_TAB, entries::accept));

        // Clientbound payload types must be known on both sides; the client entry point adds the receivers.
        PayloadTypeRegistry.playS2C().register(FluidSpawnPayload.TYPE, FluidSpawnPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidImpulsePayload.TYPE, FluidImpulsePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidCondensePayload.TYPE, FluidCondensePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidClearPayload.TYPE, FluidClearPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidSyncPayload.TYPE, FluidSyncPayload.STREAM_CODEC);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> FluidPhysicsCommand.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(ServerSimulations::tick);
        // Before the worlds are saved: the particles become blocks while their chunks are still loaded.
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerSimulations::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerSimulations.onServerStopped());
    }
}
