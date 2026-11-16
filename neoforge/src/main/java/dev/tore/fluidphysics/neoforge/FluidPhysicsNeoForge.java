// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.neoforge;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.command.FluidPhysicsCommand;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.network.FluidSyncPayload;
import dev.tore.fluidphysics.registry.ModRegistry;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(FluidPhysicsCommon.MOD_ID)
public final class FluidPhysicsNeoForge {
    /** Network protocol version; NeoForge refuses connections between different versions. */
    private static final String PROTOCOL_VERSION = "2";

    public FluidPhysicsNeoForge(IEventBus modBus) {
        FluidPhysicsCommon.init();
        modBus.addListener(RegisterEvent.class, FluidPhysicsNeoForge::register);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class,
                event -> ModRegistry.addToCreativeTab(event.getTabKey(), event::accept));
        modBus.addListener(RegisterPayloadHandlersEvent.class, FluidPhysicsNeoForge::registerPayloads);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> FluidPhysicsCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ServerSimulations.tick(level);
            }
        });
        // Before the worlds are saved: the particles become blocks while their chunks are still loaded.
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,
                event -> ServerSimulations.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ServerSimulations.onServerStopped());
    }

    /** NeoForge fires this once per registry, blocks before items (and, on 1.21.11, the chunk ticket types). */
    private static void register(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.BLOCK)) {
            ModRegistry.registerBlocks((id, block) -> event.register(Registries.BLOCK, id, () -> block));
        } else if (event.getRegistryKey().equals(Registries.ITEM)) {
            ModRegistry.registerItems((id, item) -> event.register(Registries.ITEM, id, () -> item));
        }
        //? if >=1.21.11 {
        if (event.getRegistryKey().equals(Registries.TICKET_TYPE)) {
            ModRegistry.registerTicketTypes((id, type) -> event.register(Registries.TICKET_TYPE, id, () -> type));
        }
        //?}
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // The handlers run only on the physical client, on the main (render) thread, which is the registrar's default.
        // ClientSimulation is referenced only inside the lambda bodies, so a dedicated server never loads it.
        registrar.playToClient(FluidSpawnPayload.TYPE, FluidSpawnPayload.STREAM_CODEC,
                (payload, context) -> ClientSimulation.onSpawn(payload));
        registrar.playToClient(FluidImpulsePayload.TYPE, FluidImpulsePayload.STREAM_CODEC,
                (payload, context) -> ClientSimulation.onImpulse(payload));
        registrar.playToClient(FluidCondensePayload.TYPE, FluidCondensePayload.STREAM_CODEC,
                (payload, context) -> ClientSimulation.onCondense(payload));
        registrar.playToClient(FluidClearPayload.TYPE, FluidClearPayload.STREAM_CODEC,
                (payload, context) -> ClientSimulation.onClear());
        registrar.playToClient(FluidSyncPayload.TYPE, FluidSyncPayload.STREAM_CODEC,
                (payload, context) -> ClientSimulation.onSync(payload));
    }
}
