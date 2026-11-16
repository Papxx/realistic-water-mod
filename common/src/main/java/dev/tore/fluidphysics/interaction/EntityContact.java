// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
//?} else {
/*import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
*///?}

/**
 * What particles do to the entities inside them (CLAUDE.md §3.3, Plan.md §3.4): lava sets them on fire and hurts them
 * like a vanilla lava block, water puts fires out, cushions falls, carries entities along and lifts them, slime bounces
 * falling entities back up, and every material slows down what moves through it. An entity that falls fast into
 * particles splashes them away.
 *
 * <p>Runs at the end of every entity's base tick, on both sides, against that side's own particles. Fire, damage and
 * splashes are decided on the server. Velocity changes are made on the side that moves the entity (the server for mobs
 * and items, the client for its own player), the same split vanilla uses for fluid pushing. Boats and falling blocks
 * have their own handling ({@link BoatBuoyancy}, {@link FallingBlockSplash}).
 *
 * <p>How much of an entity sits in particles is estimated from the particle counts per block
 * ({@link BlockOccupancy}): the particles of a block are taken to fill it from the bottom up, 64 to a full block. The
 * overlap of these fill levels with the entity's bounding box, as a share of the box's volume, is the entity's
 * <em>submerged share</em> per material.
 */
public final class EntityContact {
    /** Lava share above which an entity catches fire. A few stray particles at the feet do not ignite. */
    static final double IGNITE_SHARE = 0.02;
    /** Water share above which a burning entity is put out. */
    static final double EXTINGUISH_SHARE = 0.05;
    /** Water share above which the fall distance is reset, so landing in water particles does not hurt. */
    static final double CUSHION_SHARE = 0.2;
    /** The feet box reaches this far below and above the bottom of the entity, in blocks. */
    static final double FEET_DEPTH = 0.25;
    /** Share of the feet box filled with slime that makes a falling entity bounce. */
    static final double BOUNCE_SHARE = 0.25;
    /** Least downward speed that bounces off slime, in blocks per tick (walking on the ground is about 0.08). */
    static final double BOUNCE_MIN_SPEED = 0.15;
    /** Share of the downward speed a living entity keeps when bouncing off slime: the slime's restitution. */
    static final double SLIME_BOUNCE_LIVING = 0.85;
    /** Share of the downward speed other entities keep, as for falling blocks. */
    static final double SLIME_BOUNCE_OTHER = 0.7;
    /** Share of the velocity relative to the particles that is lost per tick when fully submerged, per material. */
    private static final double[] DRAG = new double[ParticleSoA.MATERIAL_COUNT];
    /**
     * Lift of water on a living entity as a share of its gravity when fully submerged. The drag also damps the speed
     * gravity added in the last tick, so gravity is only felt at 0.98 * 0.8 = 0.78 of its strength in water; at 0.7
     * players and mobs still sink slowly (about 0.6 blocks per second), as in vanilla water.
     */
    static final double LIVING_BUOYANCY = 0.7;
    /** Water share at which items and other entities float. */
    static final double FLOAT_SHARE = 0.5;
    /** Most lift on a floating entity, as a share of its gravity. */
    static final double MAX_LIFT = 1.5;
    /** Least downward speed (blocks per tick) of an entity entering particles that splashes them. */
    static final double SPLASH_MIN_SPEED = 0.35;
    /** Strongest splash push, in blocks per second. */
    static final float MAX_SPLASH_FORCE = 15f;

    static {
        DRAG[ParticleSoA.MATERIAL_WATER] = 0.2;
        DRAG[ParticleSoA.MATERIAL_LAVA] = 0.5;
        DRAG[ParticleSoA.MATERIAL_SLIME] = 0.45;
        DRAG[ParticleSoA.MATERIAL_SAND] = 0.35;
    }

    /** One sampling of the particles around a box; reused, so sampling allocates nothing. */
    public static final class Sample {
        /** Submerged share of the box per material, 0..1. */
        public final double[] submerged = new double[ParticleSoA.MATERIAL_COUNT];
        /** Share of the feet box (see {@link #FEET_DEPTH}) filled with slime, 0..1. */
        public double feetSlime;
        /** Mean velocity of the liquid particles (not sand) around the box, weighted by overlap, blocks per second. */
        public double flowX;
        public double flowY;
        public double flowZ;

        void reset() {
            for (int m = 0; m < submerged.length; m++) {
                submerged[m] = 0;
            }
            feetSlime = 0;
            flowX = 0;
            flowY = 0;
            flowZ = 0;
        }

        /** Submerged share of all materials together, at most 1. */
        public double total() {
            double sum = 0;
            for (double share : submerged) {
                sum += share;
            }
            return Math.min(1.0, sum);
        }
    }

    /** Entities tick on the server thread and on the client's main thread; each gets its own scratch. */
    private static final Sample SERVER_SAMPLE = new Sample();
    private static final Sample SERVER_PREVIOUS = new Sample();
    private static final Sample CLIENT_SAMPLE = new Sample();

    private EntityContact() {
    }

    /** Called at the end of {@code Entity.baseTick()}, before the entity moves this tick. */
    public static void afterBaseTick(Entity entity) {
        if (entity.isSpectator() || entity.noPhysics || entity instanceof FallingBlockEntity || isBoat(entity)) {
            return;
        }
        Level level = entity.level();
        boolean server = level instanceof ServerLevel;
        boolean moves = movesHere(entity);
        if (!server && !moves) {
            return;
        }
        FluidSimulation simulation = Simulations.forLevel(level);
        if (simulation == null || simulation.activeCount() == 0) {
            return;
        }
        BlockOccupancy occupancy = simulation.occupancy();
        AABB box = entity.getBoundingBox();
        Sample s = server ? SERVER_SAMPLE : CLIENT_SAMPLE;
        if (!sample(occupancy, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, s)) {
            return;
        }
        double water = s.submerged[ParticleSoA.MATERIAL_WATER];
        boolean bounces = s.feetSlime > BOUNCE_SHARE && !entity.isSuppressingBounce();
        if (server) {
            ServerLevel serverLevel = (ServerLevel) level;
            if (s.submerged[ParticleSoA.MATERIAL_LAVA] > IGNITE_SHARE) {
                burn(entity);
            } else if (water > EXTINGUISH_SHARE && entity.getRemainingFireTicks() > 0) {
                entity.clearFire();
                entity.playSound(SoundEvents.GENERIC_EXTINGUISH_FIRE, 0.7f,
                        1.6f + (entity.getRandom().nextFloat() - entity.getRandom().nextFloat()) * 0.4f);
            }
            splashIfEntering(serverLevel, entity, occupancy, box);
        }
        // On both sides: the server decides fall damage, the client predicts it for its own player.
        if (water > CUSHION_SHARE || bounces) {
            entity.resetFallDistance();
        }
        if (moves) {
            push(entity, s, bounces);
        }
    }

    /**
     * Samples the particles around the box from ({@code x0}, {@code y0}, {@code z0}) to ({@code x1}, {@code y1},
     * {@code z1}) into {@code out}.
     *
     * @return whether the box touches any particles
     */
    public static boolean sample(BlockOccupancy occupancy, double x0, double y0, double z0, double x1, double y1,
                                 double z1, Sample out) {
        out.reset();
        double volume = (x1 - x0) * (y1 - y0) * (z1 - z0);
        if (occupancy == null || volume <= 0) {
            return false;
        }
        double feetBottom = y0 - FEET_DEPTH;
        double feetTop = Math.min(y1, y0 + FEET_DEPTH);
        int bx0 = Mth.floor(x0);
        int by0 = Mth.floor(feetBottom);
        int bz0 = Mth.floor(z0);
        int bx1 = Mth.floor(x1);
        int by1 = Mth.floor(y1);
        int bz1 = Mth.floor(z1);
        if (!occupancy.mayOverlap(bx0, by0, bz0, bx1, by1, bz1)) {
            return false;
        }
        boolean touched = false;
        double flowWeight = 0;
        for (int by = by0; by <= by1; by++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                double az = overlap(bz, bz + 1, z0, z1);
                for (int bx = bx0; bx <= bx1; bx++) {
                    int slot = occupancy.slotOf(bx, by, bz);
                    double area = az * overlap(bx, bx + 1, x0, x1);
                    if (slot < 0 || area <= 0) {
                        continue;
                    }
                    for (byte m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
                        int count = occupancy.count(slot, m);
                        if (count == 0) {
                            continue;
                        }
                        double top = by + Math.min(1.0, count / (double) ParticleSoA.PARTICLES_PER_BLOCK);
                        if (m == ParticleSoA.MATERIAL_SLIME) {
                            out.feetSlime += area * overlap(by, top, feetBottom, feetTop);
                        }
                        double inside = area * overlap(by, top, y0, y1);
                        if (inside <= 0) {
                            continue;
                        }
                        touched = true;
                        out.submerged[m] += inside;
                        if (m != ParticleSoA.MATERIAL_SAND) {
                            out.flowX += occupancy.meanVelX(slot) * inside;
                            out.flowY += occupancy.meanVelY(slot) * inside;
                            out.flowZ += occupancy.meanVelZ(slot) * inside;
                            flowWeight += inside;
                        }
                    }
                }
            }
        }
        for (int m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            out.submerged[m] = Math.min(1.0, out.submerged[m] / volume);
        }
        double feetVolume = (x1 - x0) * (feetTop - feetBottom) * (z1 - z0);
        out.feetSlime = Math.min(1.0, out.feetSlime / feetVolume);
        if (flowWeight > 0) {
            out.flowX /= flowWeight;
            out.flowY /= flowWeight;
            out.flowZ /= flowWeight;
        }
        return touched || out.feetSlime > 0;
    }

    /** Length of the overlap of the ranges [a0, a1] and [b0, b1]; 0 if they do not overlap. */
    private static double overlap(double a0, double a1, double b0, double b1) {
        return Math.max(0.0, Math.min(a1, b1) - Math.max(a0, b0));
    }

    /**
     * The velocity change of one tick in particles: bouncing off slime, or drag towards the particles' own velocity
     * plus the lift of water. Velocities in blocks per tick.
     *
     * @param out receives the new velocity
     */
    static void velocityAfter(double vx, double vy, double vz, Sample s, double gravity, boolean living,
                              boolean bounces, double[] out) {
        if (bounces && vy < -BOUNCE_MIN_SPEED) {
            out[0] = vx;
            out[1] = -vy * (living ? SLIME_BOUNCE_LIVING : SLIME_BOUNCE_OTHER);
            out[2] = vz;
            return;
        }
        double drag = 0;
        for (int m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            drag += DRAG[m] * s.submerged[m];
        }
        drag = Math.min(1.0, drag);
        // Particle velocities are in blocks per second.
        double ux = s.flowX / 20.0;
        double uy = s.flowY / 20.0;
        double uz = s.flowZ / 20.0;
        out[0] = ux + (vx - ux) * (1 - drag);
        out[1] = uy + (vy - uy) * (1 - drag);
        out[2] = uz + (vz - uz) * (1 - drag);
        double water = s.submerged[ParticleSoA.MATERIAL_WATER];
        if (water > 0) {
            double lift = living ? LIVING_BUOYANCY * water : Math.min(MAX_LIFT, water / FLOAT_SHARE);
            out[1] += gravity * lift;
        }
    }

    /** Scratch for {@link #push}, per side. */
    private static final double[] SERVER_VELOCITY = new double[3];
    private static final double[] CLIENT_VELOCITY = new double[3];

    private static void push(Entity entity, Sample s, boolean bounces) {
        double[] v = entity.level().isClientSide() ? CLIENT_VELOCITY : SERVER_VELOCITY;
        Vec3 old = entity.getDeltaMovement();
        double gravity = entity.isNoGravity() ? 0.0 : entity.getGravity();
        velocityAfter(old.x, old.y, old.z, s, gravity, entity instanceof LivingEntity, bounces, v);
        entity.setDeltaMovement(v[0], v[1], v[2]);
    }

    /** Lava: sets the entity on fire for 15 seconds and hurts it, like a vanilla lava block. */
    private static void burn(Entity entity) {
        //? if >=1.21.11 {
        entity.lavaIgnite();
        //?}
        entity.lavaHurt();
    }

    /** An entity that falls fast into particles it was not in a tick ago pushes them down and away. */
    private static void splashIfEntering(ServerLevel level, Entity entity, BlockOccupancy occupancy, AABB box) {
        // Blocks per tick; for players the movement their client reported, since the server does not move them.
        Vec3 moved = entity.getKnownMovement();
        double speed = -moved.y;
        if (speed < SPLASH_MIN_SPEED || SERVER_SAMPLE.total() <= 0) {
            return;
        }
        boolean wasInside = sample(occupancy, box.minX - moved.x, box.minY - moved.y, box.minZ - moved.z,
                box.maxX - moved.x, box.maxY - moved.y, box.maxZ - moved.z, SERVER_PREVIOUS)
                && SERVER_PREVIOUS.total() > 0;
        if (!wasInside) {
            float force = (float) Math.min(MAX_SPLASH_FORCE, speed * 20.0);
            // Centred above the bottom face, so the particles below are pushed down and to the sides.
            ServerSimulations.impulse(level, entity.getX(), box.minY + 0.5, entity.getZ(),
                    1.0f + entity.getBbWidth(), force);
        }
    }

    /** Whether this side moves the entity: the server for mobs and items, a client for its own player. */
    private static boolean movesHere(Entity entity) {
        //? if >=1.21.11 {
        return entity.isLocalInstanceAuthoritative();
        //?} else {
        /*// In 1.21.1 this is also true for a server player on the server, but its client moves it.
        return entity.isControlledByLocalInstance() && !(entity instanceof Player && !entity.level().isClientSide());
        *///?}
    }

    private static boolean isBoat(Entity entity) {
        //? if >=1.21.11 {
        return entity instanceof AbstractBoat;
        //?} else {
        /*return entity instanceof Boat;
        *///?}
    }
}
