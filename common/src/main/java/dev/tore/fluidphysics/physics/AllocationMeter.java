// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.lang.management.ManagementFactory;

/**
 * Heap bytes allocated per thread, for the allocation statistics of {@code /fluidphysics stats} (Plan.md §6.5). Reads
 * HotSpot's per-thread allocation counters ({@code com.sun.management.ThreadMXBean}); on a JVM without them every
 * reading is 0 and {@link #isSupported()} is false.
 *
 * <p>Reading a counter allocates nothing (checked by {@code AllocationMeterTest}), so it can bracket the tick loop.
 */
public final class AllocationMeter {
    private static final boolean SUPPORTED = probe();

    private AllocationMeter() {
    }

    /** Whether this JVM counts allocated bytes per thread. */
    public static boolean isSupported() {
        return SUPPORTED;
    }

    /** Bytes the calling thread allocated since it started; 0 if not {@link #isSupported() supported}. */
    public static long currentThreadBytes() {
        return SUPPORTED ? HotSpot.BEAN.getCurrentThreadAllocatedBytes() : 0L;
    }

    /** Bytes thread {@code threadId} allocated since it started; 0 if it is not alive or not supported. */
    public static long threadBytes(long threadId) {
        return SUPPORTED ? Math.max(0L, HotSpot.BEAN.getThreadAllocatedBytes(threadId)) : 0L;
    }

    private static boolean probe() {
        try {
            return HotSpot.BEAN != null && HotSpot.BEAN.isThreadAllocatedMemorySupported()
                    && HotSpot.BEAN.isThreadAllocatedMemoryEnabled();
        } catch (LinkageError | RuntimeException e) {
            // No com.sun.management (a JVM other than HotSpot, or a runtime without the jdk.management module).
            return false;
        }
    }

    /** Holds the only reference to {@code com.sun.management}, so a JVM without it only fails {@link #probe()}. */
    private static final class HotSpot {
        static final com.sun.management.ThreadMXBean BEAN =
                ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    }
}
