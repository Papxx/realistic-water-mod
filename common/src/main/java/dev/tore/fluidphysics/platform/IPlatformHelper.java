package dev.tore.fluidphysics.platform;

/**
 * Everything common code needs from the mod loader. Each loader module provides one implementation,
 * published through {@code META-INF/services} and looked up by {@link Services}.
 */
public interface IPlatformHelper {
    /** Human-readable loader name, e.g. "Fabric" or "NeoForge". */
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();
}
