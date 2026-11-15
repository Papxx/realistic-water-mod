// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.fabric;

import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client entry point (fabric.mod.json "client"); never loaded on a dedicated server. */
public final class FluidPhysicsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
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
    }
}
