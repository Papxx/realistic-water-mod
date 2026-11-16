// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RollingSamplesTest {
    @Test
    void emptySamplesReadAsZero() {
        RollingSamples samples = new RollingSamples(4);
        assertEquals(0, samples.count());
        assertEquals(0L, samples.last());
        assertEquals(0L, samples.mean());
        assertEquals(0L, samples.max());
        assertEquals(0L, samples.percentile(0.99));
        samples.addToLast(5L);
        assertEquals(0L, samples.last(), "addToLast does nothing while empty");
    }

    @Test
    void keepsOnlyTheNewestValues() {
        RollingSamples samples = new RollingSamples(4);
        for (long v = 1; v <= 6; v++) {
            samples.add(v * 10);
        }
        // Holds 30, 40, 50, 60.
        assertEquals(4, samples.count());
        assertEquals(60L, samples.last());
        assertEquals(45L, samples.mean());
        assertEquals(60L, samples.max());
        samples.addToLast(5L);
        assertEquals(65L, samples.last());
        samples.clear();
        assertEquals(0, samples.count());
        assertEquals(0L, samples.max());
    }

    @Test
    void percentilesUseTheNearestRank() {
        RollingSamples samples = new RollingSamples(200);
        // 1..200 in scrambled order.
        for (int i = 0; i < 200; i++) {
            samples.add((i * 77) % 200 + 1);
        }
        assertEquals(100L, samples.percentile(0.5));
        assertEquals(198L, samples.percentile(0.99));
        assertEquals(200L, samples.percentile(1.0));
        assertEquals(1L, samples.percentile(0.0));
        assertEquals(200L, samples.max(), "percentile must not reorder the samples");
        assertEquals(1L + (199 * 77) % 200, samples.last());
    }
}
