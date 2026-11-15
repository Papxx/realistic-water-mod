// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.item.PhysicsBucketItem;
import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.registry.ModRegistry;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Filling an empty vanilla bucket from particles (Plan.md §4.1): the look ray finds the first water, lava or slime
 * particles in reach, and the 64 particles of that material nearest to that point (within {@link #GATHER_RADIUS})
 * go into the bucket. With fewer than 64 there, nothing happens, so a bucket never holds more than was picked up.
 *
 * <p>The server removes the particles and tells the clients; a client only predicts the outcome from its own
 * particles so the hand animation matches.
 */
public final class ParticlePickup {
    /** Radius around the hit point the particles are taken from, in blocks. */
    public static final float GATHER_RADIUS = 2.5f;
    /** Step along the look ray, in blocks: a quarter block, one sub-voxel. */
    private static final double RAY_STEP = 0.25;

    private ParticlePickup() {
    }

    /**
     * Tries to fill the empty bucket {@code stack}. Returns the stack the hand holds afterwards, or null if there are
     * not enough particles in reach (vanilla bucket behaviour then continues).
     */
    public static ItemStack tryFill(Level level, Player player, ItemStack stack) {
        if (!stack.is(Items.BUCKET)) {
            return null;
        }
        FluidSimulation simulation = Simulations.forLevel(level);
        if (simulation == null || simulation.activeCount() < ParticleSoA.PARTICLES_PER_BLOCK) {
            return null;
        }
        BlockOccupancy occupancy = simulation.occupancy();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        double reach = player.blockInteractionRange();
        // Particles behind the first solid block are out of reach.
        BlockHitResult wall = level.clip(new ClipContext(eye, eye.add(look.scale(reach)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (wall.getType() == HitResult.Type.BLOCK) {
            reach = wall.getLocation().distanceTo(eye);
        }
        for (double t = 0; t <= reach; t += RAY_STEP) {
            double x = eye.x + look.x * t;
            double y = eye.y + look.y * t;
            double z = eye.z + look.z * t;
            int slot = occupancy.slotOf(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            if (slot < 0) {
                continue;
            }
            byte material = majorityLiquid(occupancy, slot);
            if (material >= 0) {
                return fill(level, player, stack, simulation, material, x, y, z);
            }
        }
        return null;
    }

    /** The bucket material with the most particles in a slot, or -1 if it holds none (sand has no bucket). */
    private static byte majorityLiquid(BlockOccupancy occupancy, int slot) {
        byte best = -1;
        int bestCount = 0;
        for (byte m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            int count = occupancy.count(slot, m);
            if (ModRegistry.bucketFor(m) != null && count > bestCount) {
                best = m;
                bestCount = count;
            }
        }
        return best;
    }

    private static ItemStack fill(Level level, Player player, ItemStack stack, FluidSimulation simulation,
                                  byte material, double x, double y, double z) {
        int needed = ParticleSoA.PARTICLES_PER_BLOCK;
        if (level instanceof ServerLevel server) {
            if (simulation.markNearest(x, y, z, material, needed, GATHER_RADIUS, false, true) < needed) {
                return null;
            }
            ServerSimulations.removeMarked(server, simulation);
            level.playSound(null, player.blockPosition(), PhysicsBucketItem.fillSound(material), SoundSource.PLAYERS,
                    1f, 1f);
            level.gameEvent(player, GameEvent.FLUID_PICKUP, player.blockPosition());
            player.awardStat(Stats.ITEM_USED.get(Items.BUCKET));
        } else if (simulation.countNear(x, y, z, material, GATHER_RADIUS) < needed) {
            return null;
        }
        return ItemUtils.createFilledResult(stack, player, new ItemStack(ModRegistry.bucketFor(material)));
    }
}
