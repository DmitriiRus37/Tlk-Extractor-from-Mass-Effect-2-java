package ru;

import java.util.concurrent.ForkJoinPool;

/**
 * The thread pool for the parallel parts of the conversions (decoding in {@link TlkFile}, text
 * export, Huffman encoding in {@link HuffmanCompression}, TXT and CSV parsing).
 *
 * <p>It has at most {@value #MAX_THREADS} threads rather than one per core: the tool usually
 * runs one conversion per JVM, before the JIT has compiled anything, and on a 14-core machine
 * 13 threads made a cold export of {@code BIOGame_RUS.tlk} up to twice as slow as a sequential
 * one (they take the CPU from the JIT compiler threads), while 4 threads were as fast cold and
 * 3-5 times as fast warm. The threads are daemon threads, created on first use.
 */
final class Workers {

    /** Upper limit of the pool size. */
    static final int MAX_THREADS = 4;

    /** Not instantiable. */
    private Workers() {
    }

    /** Holds the pool, so that it is only created when first used. */
    private static final class Holder {
        /** The pool. */
        static final ForkJoinPool POOL =
                new ForkJoinPool(Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors())));
    }

    /**
     * Returns the pool.
     *
     * @return the shared pool
     */
    static ForkJoinPool pool() {
        return Holder.POOL;
    }
}
