// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Explosions blow particles away (Plan.md §4.5): every particle within twice the explosion radius gets a radial
 * push, strongest at the centre. The push is sent to the clients as one impulse event.
 */
public final class ExplosionPush {
    /** Push at the centre: a base plus this much per block of explosion radius, in blocks per second. */
    private static final float FORCE_BASE = 6f;
    private static final float FORCE_PER_RADIUS = 4f;

    private ExplosionPush() {
    }

    /** Called after an explosion has destroyed its blocks (so particles freed by it are pushed too). */
    public static void afterExplosion(Level level, Vec3 center, float radius) {
        if (level instanceof ServerLevel server && radius > 0f) {
            ServerSimulations.impulse(server, center.x, center.y, center.z, radius * 2f,
                    FORCE_BASE + FORCE_PER_RADIUS * radius);
        }
    }
}
