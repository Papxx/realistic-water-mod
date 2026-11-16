// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SLIME;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.WorkerPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class EntityContactTest {
    private static final WorkerPool POOL = new WorkerPool(1);
    /** Player-sized box: 0.6 wide, 1.8 high. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double ITEM_GRAVITY = 0.04;

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    private static boolean sampleBoxAt(FluidSimulation sim, double x, double y, double z,
                                       EntityContact.Sample out) {
        return EntityContact.sample(sim.occupancy(), x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT,
                z + HALF_WIDTH, out);
    }

    @Test
    void aFullBlockOfWaterSubmergesTheLowerPartOfAStandingPlayer() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 10, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        EntityContact.Sample s = new EntityContact.Sample();
        assertTrue(sampleBoxAt(sim, 0.5, 10, 0.5, s));
        // The block is full, so the bottom block of the 1.8 high box is under water.
        assertEquals(1.0 / HEIGHT, s.submerged[MATERIAL_WATER], 1e-6);
        assertEquals(0.0, s.submerged[MATERIAL_LAVA]);
    }

    @Test
    void particlesFillTheirBlockFromTheBottom() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 10, 0, MATERIAL_LAVA, 32, 0f, 0f, 0f);
        EntityContact.Sample s = new EntityContact.Sample();
        assertTrue(sampleBoxAt(sim, 0.5, 10, 0.5, s));
        assertEquals(0.5 / HEIGHT, s.submerged[MATERIAL_LAVA], 1e-6);
        // Standing on top of the half block: no contact.
        assertFalse(sampleBoxAt(sim, 0.5, 10.6, 0.5, s));
    }

    @Test
    void aBoxFarFromAllParticlesTouchesNothing() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 10, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        EntityContact.Sample s = new EntityContact.Sample();
        assertFalse(sampleBoxAt(sim, 40.5, 10, 0.5, s));
        assertEquals(0.0, s.total());
    }

    @Test
    void flowIsTheParticlesMeanVelocity() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 10, 0, MATERIAL_WATER, 64, 2f, 0f, 0f);
        EntityContact.Sample s = new EntityContact.Sample();
        assertTrue(sampleBoxAt(sim, 0.5, 10, 0.5, s));
        assertEquals(2.0, s.flowX, 1e-5);
        assertEquals(0.0, s.flowZ, 1e-5);

        double[] v = new double[3];
        EntityContact.velocityAfter(0, 0, 0, s, 0, true, false, v);
        assertTrue(v[0] > 0, "a stream carries a resting entity along");
    }

    @Test
    void slimeUnderTheFeetBouncesAFallingEntity() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 10, 0, MATERIAL_SLIME, 64, 0f, 0f, 0f);
        EntityContact.Sample s = new EntityContact.Sample();
        // Just above the slime: the box itself is out of it, its feet are in it.
        assertTrue(sampleBoxAt(sim, 0.5, 11.1, 0.5, s));
        assertEquals(0.0, s.submerged[MATERIAL_SLIME]);
        assertTrue(s.feetSlime > EntityContact.BOUNCE_SHARE, "feet share " + s.feetSlime);

        double[] v = new double[3];
        EntityContact.velocityAfter(0.1, -0.5, 0, s, PLAYER_GRAVITY, true, true, v);
        assertEquals(0.5 * EntityContact.SLIME_BOUNCE_LIVING, v[1], 1e-9);
        assertEquals(0.1, v[0], 1e-9);
        EntityContact.velocityAfter(0, -0.5, 0, s, 0.04, false, true, v);
        assertEquals(0.5 * EntityContact.SLIME_BOUNCE_OTHER, v[1], 1e-9);
        // Walking on the ground (gravity pulls about 0.08 per tick) does not bounce.
        EntityContact.velocityAfter(0, -0.0784, 0, s, PLAYER_GRAVITY, true, true, v);
        assertTrue(v[1] < 0);
    }

    /**
     * Vertical speed in steady state for an entity fully under water, using vanilla's order: the base tick (and with
     * it the particles) changes the velocity, the entity moves, then gravity and air drag (0.98) apply.
     *
     * @return the distance moved per tick once the speed settled (negative: sinking)
     */
    private static double settledMove(double gravity, boolean living) {
        EntityContact.Sample s = new EntityContact.Sample();
        s.submerged[MATERIAL_WATER] = 1.0;
        double[] v = new double[3];
        double vy = 0;
        double moved = 0;
        for (int tick = 0; tick < 400; tick++) {
            EntityContact.velocityAfter(0, vy, 0, s, gravity, living, false, v);
            moved = v[1];
            vy = (moved - gravity) * 0.98;
        }
        return moved;
    }

    @Test
    void playersSinkSlowlyInWaterWhileItemsFloatUp() {
        double player = settledMove(PLAYER_GRAVITY, true);
        assertTrue(player < 0 && player > -0.05, "player moves " + player + " blocks per tick");
        double item = settledMove(ITEM_GRAVITY, false);
        assertTrue(item > 0, "item moves " + item + " blocks per tick");
    }
}
