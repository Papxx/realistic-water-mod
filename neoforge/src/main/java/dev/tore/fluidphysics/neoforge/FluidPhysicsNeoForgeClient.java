// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.neoforge;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.client.ClientSimulation;
import dev.tore.fluidphysics.client.render.ParticleBatchRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point; FML constructs it only on the physical client. */
@Mod(value = FluidPhysicsCommon.MOD_ID, dist = Dist.CLIENT)
public final class FluidPhysicsNeoForgeClient {
    public FluidPhysicsNeoForgeClient() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> ClientSimulation.tick(Minecraft.getInstance()));

        // Right after translucent terrain.
        //? if >=1.21.11 {
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentBlocks.class,
                event -> ParticleBatchRenderer.render());
        //?} else {
        /*NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                ParticleBatchRenderer.render();
            }
        });
        *///?}
    }
}
