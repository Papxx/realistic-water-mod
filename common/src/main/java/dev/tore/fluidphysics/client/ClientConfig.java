// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.platform.Services;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The client settings that survive a restart (the {@link RenderMode}), stored in
 * {@code config/fluidphysics-client.properties}. Read on first use and written whenever a setting changes. A missing
 * or broken file means the defaults; failures are logged, never thrown, and without a platform (unit tests) the
 * defaults apply.
 *
 * <p>Client classes: only the loaders' client entry points and the classes they reach may reference this class. Render
 * thread only (client commands run there as well).
 */
public final class ClientConfig {
    private static final String FILE = "fluidphysics-client.properties";
    private static final String RENDER_MODE = "renderMode";
    /** Default render mode: the smooth surface (Plan.md §8.3). */
    public static final RenderMode DEFAULT_RENDER_MODE = RenderMode.SMOOTH;

    private static boolean loaded;
    private static RenderMode renderMode = DEFAULT_RENDER_MODE;

    private ClientConfig() {
    }

    /** The current render mode; read once per frame, allocation-free after the first call. */
    public static RenderMode renderMode() {
        ensureLoaded();
        return renderMode;
    }

    /**
     * Sets the render mode and saves the settings if it changed.
     *
     * @return whether the mode changed
     */
    public static boolean setRenderMode(RenderMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        ensureLoaded();
        if (mode == renderMode) {
            return false;
        }
        renderMode = mode;
        save();
        return true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    /** Reads the file; keeps the defaults when it does not exist, can not be read or the platform is unavailable. */
    private static void load() {
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve(FILE);
            if (Files.isRegularFile(file)) {
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
                String value = properties.getProperty(RENDER_MODE, DEFAULT_RENDER_MODE.id());
                RenderMode mode = RenderMode.byId(value);
                if (mode == null) {
                    FluidPhysicsCommon.LOGGER.warn("Unknown {} '{}' in {}, using {}", RENDER_MODE, value, FILE,
                            DEFAULT_RENDER_MODE.id());
                    mode = DEFAULT_RENDER_MODE;
                }
                renderMode = mode;
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            FluidPhysicsCommon.LOGGER.warn("Could not read {}, using defaults: {}", FILE, e.toString());
            renderMode = DEFAULT_RENDER_MODE;
        }
    }

    private static void save() {
        try {
            Path dir = Services.PLATFORM.getConfigDir();
            Files.createDirectories(dir);
            Properties properties = new Properties();
            properties.setProperty(RENDER_MODE, renderMode.id());
            try (OutputStream out = Files.newOutputStream(dir.resolve(FILE))) {
                properties.store(out, "Fluid physics client settings (changed with /fluidphysics_client)");
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            FluidPhysicsCommon.LOGGER.warn("Could not save {}: {}", FILE, e.toString());
        }
    }
}
