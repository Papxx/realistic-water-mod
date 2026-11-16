// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.platform;

import java.nio.file.Path;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything common code needs from the mod loader. Each loader module provides one implementation,
 * published through {@code META-INF/services} and looked up by {@link Services}.
 */
public interface IPlatformHelper {
    /** Human-readable loader name, e.g. "Fabric" or "NeoForge". */
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** The loader's config directory. */
    Path getConfigDir();

    /**
     * Sends a registered clientbound payload to one player. Players whose client can not receive it (no mod installed)
     * are skipped.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
