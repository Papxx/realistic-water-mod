package dev.tore.fluidphysics;

import com.mojang.logging.LogUtils;
import dev.tore.fluidphysics.platform.Services;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Loader-independent entry point. Each loader module calls {@link #init()} once while the mod is constructed.
 */
public final class FluidPhysicsCommon {
    public static final String MOD_ID = "fluidphysics";
    public static final Logger LOGGER = LogUtils.getLogger();

    private FluidPhysicsCommon() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void init() {
        LOGGER.info("Initializing {} on {}", MOD_ID, Services.PLATFORM.getPlatformName());
    }
}
