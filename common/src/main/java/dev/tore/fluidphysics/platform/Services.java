package dev.tore.fluidphysics.platform;

import java.util.ServiceLoader;

/** Loader-specific implementations of the common service interfaces, resolved once at class load. */
public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> service) {
        return ServiceLoader.load(service)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation registered for " + service.getName()));
    }
}
