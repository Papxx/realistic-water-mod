// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.platform.Services;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The server settings that survive a restart ({@code /fluidphysics limit} and {@code floodgates}), stored in
 * {@code config/fluidphysics.properties}. A missing or broken file means the defaults; failures are logged, never thrown.
 */
final class FluidConfig {
    private static final String FILE = "fluidphysics.properties";
    private static final String LIMIT = "particleLimit";
    private static final String FLOODGATES = "floodgates";

    int limit = ServerSimulations.DEFAULT_LIMIT;
    boolean floodgates;

    /** Reads the file; returns the defaults when it does not exist or the platform is unavailable (unit tests). */
    static FluidConfig load() {
        FluidConfig config = new FluidConfig();
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve(FILE);
            if (Files.isRegularFile(file)) {
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
                config.limit = Integer.parseInt(properties.getProperty(LIMIT, Integer.toString(config.limit)).trim());
                config.floodgates = Boolean.parseBoolean(properties.getProperty(FLOODGATES, "false").trim());
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            FluidPhysicsCommon.LOGGER.warn("Could not read {}, using defaults: {}", FILE, e.toString());
            config = new FluidConfig();
        }
        return config;
    }

    void save() {
        try {
            Path dir = Services.PLATFORM.getConfigDir();
            Files.createDirectories(dir);
            Properties properties = new Properties();
            properties.setProperty(LIMIT, Integer.toString(limit));
            properties.setProperty(FLOODGATES, Boolean.toString(floodgates));
            try (OutputStream out = Files.newOutputStream(dir.resolve(FILE))) {
                properties.store(out, "Fluid physics server settings (changed with /fluidphysics)");
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            FluidPhysicsCommon.LOGGER.warn("Could not save {}: {}", FILE, e.toString());
        }
    }
}
