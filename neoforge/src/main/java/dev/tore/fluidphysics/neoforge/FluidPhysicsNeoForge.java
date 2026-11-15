package dev.tore.fluidphysics.neoforge;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import net.neoforged.fml.common.Mod;

@Mod(FluidPhysicsCommon.MOD_ID)
public final class FluidPhysicsNeoForge {
    public FluidPhysicsNeoForge() {
        FluidPhysicsCommon.init();
    }
}
