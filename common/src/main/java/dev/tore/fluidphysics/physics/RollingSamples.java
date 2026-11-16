// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.util.Arrays;

/**
 * The last {@code window} values of one measurement (a time in nanoseconds, a byte count) with their mean, maximum and
 * percentiles. Adding a value allocates nothing; {@link #percentile} sorts a scratch copy, so call it outside hot loops.
 * Not thread-safe.
 */
public final class RollingSamples {
    private final long[] values;
    private final long[] scratch;
    /** Slot of the next value. */
    private int cursor;
    private int count;

    public RollingSamples(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least 1, got " + window);
        }
        values = new long[window];
        scratch = new long[window];
    }

    public void add(long value) {
        values[cursor] = value;
        cursor = (cursor + 1) % values.length;
        count = Math.min(count + 1, values.length);
    }

    /** Adds {@code delta} to the newest value; does nothing while empty. */
    public void addToLast(long delta) {
        if (count > 0) {
            values[(cursor + values.length - 1) % values.length] += delta;
        }
    }

    public void clear() {
        cursor = 0;
        count = 0;
    }

    /** Values held, at most the window. */
    public int count() {
        return count;
    }

    public int window() {
        return values.length;
    }

    /** The newest value; 0 while empty. */
    public long last() {
        return count == 0 ? 0L : values[(cursor + values.length - 1) % values.length];
    }

    /** Mean of the values held; 0 while empty. */
    public long mean() {
        if (count == 0) {
            return 0L;
        }
        long sum = 0L;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return sum / count;
    }

    /** Largest value held; 0 while empty. */
    public long max() {
        long max = 0L;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, values[i]);
        }
        return max;
    }

    /** Nearest-rank percentile of the values held ({@code fraction} 0..1, so 0.99 is p99); 0 while empty. */
    public long percentile(double fraction) {
        if (count == 0) {
            return 0L;
        }
        System.arraycopy(values, 0, scratch, 0, count);
        Arrays.sort(scratch, 0, count);
        int rank = (int) Math.ceil(fraction * count);
        return scratch[Math.max(0, Math.min(count - 1, rank - 1))];
    }
}
