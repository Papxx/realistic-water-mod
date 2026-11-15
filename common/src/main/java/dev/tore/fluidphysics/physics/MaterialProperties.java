// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_COUNT;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SLIME;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;

/**
 * Per-material solver parameters (Plan.md §3.4), indexed by the material id stored in {@link ParticleSoA#material}.
 * Plain arrays so the solver's hot loops read them without any indirection.
 */
public final class MaterialProperties {
    /** Vertical acceleration in blocks per second squared. */
    static final float[] GRAVITY = new float[MATERIAL_COUNT];
    /** XSPH viscosity: fraction of the way each substep a particle's velocity moves towards its neighbours' mean. */
    static final float[] VISCOSITY = new float[MATERIAL_COUNT];
    /**
     * Scales the density constraint when a particle is under-dense (C &lt; 0). 0 makes the constraint purely
     * repulsive; larger values pull neighbours together (surface tension, slime elasticity).
     */
    static final float[] COHESION = new float[MATERIAL_COUNT];
    /** Fraction of tangential velocity removed per substep while touching a wall or floor. */
    static final float[] WALL_FRICTION = new float[MATERIAL_COUNT];
    /** Fraction of the normal velocity kept (and reversed) when hitting a wall or floor. */
    static final float[] RESTITUTION = new float[MATERIAL_COUNT];
    /**
     * Granular materials skip the fluid density constraint and use particle contacts with Coulomb friction instead,
     * which lets them pile up at an angle of repose.
     */
    static final boolean[] GRANULAR = new boolean[MATERIAL_COUNT];
    /** Lower-case name, as used by commands. */
    private static final String[] NAMES = new String[MATERIAL_COUNT];

    static {
        set(MATERIAL_WATER, "water", -9.81f, 0.02f, 0.01f, 0.02f, 0.10f, false);
        set(MATERIAL_LAVA, "lava", -9.81f, 0.45f, 0.08f, 0.25f, 0.00f, false);
        set(MATERIAL_SLIME, "slime", -9.81f, 0.35f, 0.30f, 0.60f, 0.85f, false);
        set(MATERIAL_SAND, "sand", -12.0f, 0.15f, 0.00f, 0.75f, 0.05f, true);
    }

    private MaterialProperties() {
    }

    private static void set(byte mat, String name, float gravity, float viscosity, float cohesion, float friction,
                            float restitution, boolean granular) {
        NAMES[mat] = name;
        GRAVITY[mat] = gravity;
        VISCOSITY[mat] = viscosity;
        COHESION[mat] = cohesion;
        WALL_FRICTION[mat] = friction;
        RESTITUTION[mat] = restitution;
        GRANULAR[mat] = granular;
    }

    public static float gravity(byte mat) {
        return GRAVITY[mat];
    }

    public static boolean isGranular(byte mat) {
        return GRANULAR[mat];
    }

    /** Whether {@code mat} is a known material id; ids from the network must be checked before use. */
    public static boolean isValid(int mat) {
        return mat >= 0 && mat < MATERIAL_COUNT;
    }

    /** Lower-case name of a valid material id, e.g. "water". */
    public static String name(byte mat) {
        return NAMES[mat];
    }
}
