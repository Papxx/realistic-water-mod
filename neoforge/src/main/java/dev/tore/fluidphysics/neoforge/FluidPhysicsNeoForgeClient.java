// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.neoforge;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.client.ClientSimulation;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point; FML constructs it only on the physical client. */
@Mod(value = FluidPhysicsCommon.MOD_ID, dist = Dist.CLIENT)
public final class FluidPhysicsNeoForgeClient {
    public FluidPhysicsNeoForgeClient() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> ClientSimulation.tick(Minecraft.getInstance()));
    }
}
