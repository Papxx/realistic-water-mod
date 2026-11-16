// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class AllocationMeterTest {
    private static volatile Object sink;

    @Test
    void countsAllocationsOfTheCallingThread() {
        assumeTrue(AllocationMeter.isSupported(), "this JVM has no per-thread allocation counters");
        long before = AllocationMeter.currentThreadBytes();
        sink = new byte[1 << 20];
        long allocated = AllocationMeter.currentThreadBytes() - before;
        assertTrue(allocated >= 1 << 20, "a 1 MiB array counted as " + allocated + " bytes");
        assertEquals(AllocationMeter.currentThreadBytes(), AllocationMeter.threadBytes(Thread.currentThread().threadId()),
                10_000, "both readings of the calling thread agree");
    }

    @Test
    void readingTheCountersAllocatesNothing() {
        assumeTrue(AllocationMeter.isSupported(), "this JVM has no per-thread allocation counters");
        WorkerPool pool = new WorkerPool(3);
        try {
            long sum = 0L;
            // Warm up, so neither class loading nor the JIT shows up as allocations.
            for (int i = 0; i < 20_000; i++) {
                sum += AllocationMeter.currentThreadBytes() + pool.workerAllocatedBytes();
            }
            long before = AllocationMeter.currentThreadBytes();
            for (int i = 0; i < 10_000; i++) {
                sum += AllocationMeter.currentThreadBytes() + pool.workerAllocatedBytes();
            }
            long allocated = AllocationMeter.currentThreadBytes() - before;
            assertTrue(sum > 0L);
            assertEquals(0L, allocated, "20,000 readings allocated " + allocated + " bytes");
        } finally {
            pool.close();
        }
    }
}
