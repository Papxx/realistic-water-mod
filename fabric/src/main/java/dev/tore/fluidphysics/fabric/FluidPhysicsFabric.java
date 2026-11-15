package dev.tore.fluidphysics.fabric;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import net.fabricmc.api.ModInitializer;

public final class FluidPhysicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FluidPhysicsCommon.init();
    }
}
