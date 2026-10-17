package ru;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkersTest {

    private static <T> CompletableFuture<T> failing(RuntimeException failure) {
        return CompletableFuture.supplyAsync(() -> {
            throw failure;
        }, Workers.pool());
    }

    @Test
    void joinReturnsTheResult() throws IOException {
        assertEquals("done", Workers.join(CompletableFuture.supplyAsync(() -> "done", Workers.pool())));
    }

    @Test
    void joinRethrowsFailuresUnwrapped() {
        IOException io = new IOException("disk full");
        assertSame(io, assertThrows(IOException.class, () -> Workers.join(failing(new UncheckedIOException(io)))));
        IllegalStateException state = new IllegalStateException("too large");
        assertSame(state, assertThrows(IllegalStateException.class, () -> Workers.join(failing(state))));
        OutOfMemoryError oom = new OutOfMemoryError("heap");
        CompletableFuture<Object> error = CompletableFuture.supplyAsync(() -> {
            throw oom;
        }, Workers.pool());
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> Workers.join(error)));
    }

    @Test
    void poolHasAtMostFourThreads() {
        assertTrue(Workers.pool().getParallelism() <= Workers.MAX_THREADS);
    }
}
