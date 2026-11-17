// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import java.util.Locale;

/**
 * How the client draws its fluid particles (Plan.md §8): {@link #SMOOTH} meshes water, lava and slime into one glossy
 * iso-surface, {@link #CUBES} draws every particle as a micro-cube (the look before Step 8, and cheaper). Sand is drawn
 * as cubes in both modes. Set with {@code /fluidphysics_client rendermode} and stored in {@link ClientConfig}.
 */
public enum RenderMode {
    SMOOTH,
    CUBES;

    /** The name used in commands and in the config file: the constant's name in lower case. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode with the given {@link #id()} (case and surrounding blanks ignored), or null if there is none. */
    public static RenderMode byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim();
        for (RenderMode mode : values()) {
            if (mode.id().equalsIgnoreCase(wanted)) {
                return mode;
            }
        }
        return null;
    }
}
