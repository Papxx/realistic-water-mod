// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * A fixed set of threads that split one index range at a time between them. The calling thread does the first
 * share itself, so a pool of size 1 runs everything inline without any threads.
 *
 * <p>Dispatching a range allocates nothing: tasks are long-lived objects (e.g. method references stored in fields),
 * workers wait by spinning briefly and then parking, and completion is tracked with one atomic counter.
 *
 * <p>{@link #run} must only be called from one thread at a time.
 */
public final class WorkerPool implements AutoCloseable {
    /** Work on {@code [from, to)} as worker {@code worker} (0 = the calling thread). */
    @FunctionalInterface
    public interface RangeTask {
        void run(int from, int to, int worker);
    }

    /** Ranges smaller than this run on the calling thread alone; waking workers would cost more. */
    private static final int MIN_PARALLEL_RANGE = 2048;
    /** How long an idle worker keeps spinning before it parks; a solver step dispatches every few microseconds. */
    private static final long SPIN_NANOS = 100_000L;

    private final int size;
    private final Thread[] workers;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger parked = new AtomicInteger();

    private volatile RangeTask task;
    private volatile int rangeSize;
    private volatile long generation;
    private volatile Throwable failure;
    private volatile boolean closed;

    /** @param size total number of threads including the caller, at least 1 */
    public WorkerPool(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, got " + size);
        }
        this.size = size;
        workers = new Thread[size - 1];
        for (int w = 1; w < size; w++) {
            int id = w;
            Thread thread = new Thread(() -> workLoop(id), "FluidPhysics-Worker-" + w);
            thread.setDaemon(true);
            workers[w - 1] = thread;
            thread.start();
        }
    }

    /**
     * A sensible size for this machine: half the logical processors, at most 8. With SMT two logical processors share
     * one core's floating-point units, so more threads barely help the solver, and the game needs cores of its own.
     */
    public static int defaultSize() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    public int size() {
        return size;
    }

    /** How many shares {@link #run} splits a range of {@code n} into: workers {@code 0..sharesFor(n)-1} take part. */
    public int sharesFor(int n) {
        return n < MIN_PARALLEL_RANGE ? 1 : size;
    }

    /** Runs {@code task} over {@code [0, n)} split into one contiguous share per thread, and waits for all shares. */
    public void run(RangeTask task, int n) {
        if (sharesFor(n) == 1) {
            task.run(0, n, 0);
            return;
        }
        if (closed) {
            throw new IllegalStateException("WorkerPool is closed");
        }
        this.task = task;
        this.rangeSize = n;
        pending.set(size - 1);
        // The volatile write publishes task and rangeSize to the workers.
        generation = generation + 1;
        if (parked.get() > 0) {
            for (Thread worker : workers) {
                LockSupport.unpark(worker);
            }
        }
        task.run(0, shareEnd(n, 0), 0);
        while (pending.get() != 0) {
            Thread.onSpinWait();
        }
        Throwable error = failure;
        if (error != null) {
            failure = null;
            throw new IllegalStateException("A worker failed", error);
        }
    }

    private int shareEnd(int n, int worker) {
        return (int) ((long) n * (worker + 1) / size);
    }

    private void workLoop(int id) {
        long seen = 0L;
        while (true) {
            long current = awaitGeneration(seen);
            if (current < 0) {
                return;
            }
            seen = current;
            int n = rangeSize;
            try {
                task.run(shareEnd(n, id - 1), shareEnd(n, id), id);
            } catch (Throwable t) {
                failure = t;
            }
            pending.decrementAndGet();
        }
    }

    /** Waits until the generation differs from {@code seen}; returns it, or -1 once the pool is closed. */
    private long awaitGeneration(long seen) {
        long spinUntil = System.nanoTime() + SPIN_NANOS;
        while (true) {
            if (closed) {
                return -1L;
            }
            long current = generation;
            if (current != seen) {
                return current;
            }
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
                continue;
            }
            // Re-check after announcing ourselves, so a dispatch between the check and park() is not missed.
            parked.incrementAndGet();
            if (generation == seen && !closed) {
                LockSupport.park(this);
            }
            parked.decrementAndGet();
            spinUntil = System.nanoTime() + SPIN_NANOS;
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
        }
    }
}
