package dev.tore.fluidphysics.neoforge.platform;

import dev.tore.fluidphysics.platform.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public final class NeoForgePlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        // FMLLoader stopped being a static singleton in NeoForge 21.9.
        //? if >=1.21.9 {
        return !FMLLoader.getCurrent().isProduction();
        //?} else {
        /*return !FMLLoader.isProduction();
        *///?}
    }
}
