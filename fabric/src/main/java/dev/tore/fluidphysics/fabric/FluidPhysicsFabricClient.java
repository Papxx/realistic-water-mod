// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.fabric;

import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.client.ClientStatsCommand;
import dev.tore.fluidphysics.client.render.ParticleBatchRenderer;
import dev.tore.fluidphysics.interaction.Simulations;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >=1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}

/** Client entry point (fabric.mod.json "client"); never loaded on a dedicated server. */
public final class FluidPhysicsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Simulations.setClientLookup(ClientSimulation::lookup);

        // Fabric calls these receivers on the render thread, where the client simulation also ticks.
        ClientPlayNetworking.registerGlobalReceiver(FluidSpawnPayload.TYPE,
                (payload, context) -> ClientSimulation.onSpawn(payload));
        ClientPlayNetworking.registerGlobalReceiver(FluidImpulsePayload.TYPE,
                (payload, context) -> ClientSimulation.onImpulse(payload));
        ClientPlayNetworking.registerGlobalReceiver(FluidCondensePayload.TYPE,
                (payload, context) -> ClientSimulation.onCondense(payload));
        ClientPlayNetworking.registerGlobalReceiver(FluidClearPayload.TYPE,
                (payload, context) -> ClientSimulation.onClear());

        ClientTickEvents.END_CLIENT_TICK.register(ClientSimulation::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                ClientStatsCommand.register(dispatcher, FabricClientCommandSource::sendFeedback));

        // After translucent terrain, before clouds and weather (and, on 1.21.11, before vanilla particles).
        //? if >=1.21.11 {
        WorldRenderEvents.END_MAIN.register(context -> ParticleBatchRenderer.render());
        //?} else {
        /*WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> ParticleBatchRenderer.render());
        *///?}
    }
}
