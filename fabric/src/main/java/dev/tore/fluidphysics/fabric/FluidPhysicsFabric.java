// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.fabric;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.command.FluidPhysicsCommand;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class FluidPhysicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FluidPhysicsCommon.init();

        // Clientbound payload types must be known on both sides; the client entry point adds the receivers.
        PayloadTypeRegistry.playS2C().register(FluidSpawnPayload.TYPE, FluidSpawnPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidImpulsePayload.TYPE, FluidImpulsePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidCondensePayload.TYPE, FluidCondensePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(FluidClearPayload.TYPE, FluidClearPayload.STREAM_CODEC);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> FluidPhysicsCommand.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(ServerSimulations::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerSimulations.onServerStopped());
    }
}
