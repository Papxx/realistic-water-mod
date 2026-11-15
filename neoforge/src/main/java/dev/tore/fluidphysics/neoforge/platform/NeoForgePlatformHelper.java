// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.neoforge.platform;

import dev.tore.fluidphysics.platform.IPlatformHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.PacketDistributor;

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

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
