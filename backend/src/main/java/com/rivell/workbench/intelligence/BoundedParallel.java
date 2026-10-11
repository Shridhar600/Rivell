package com.rivell.workbench.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Runs independent decisions (one per record or clause) concurrently, at most {@code limit} at a time, and
 * returns one outcome per input in input order. A failed item carries its exception so the
 * caller keeps its per-item degradation rules; nothing is retried or dropped.
 */
public final class BoundedParallel {

    private BoundedParallel() {
    }

    public record Outcome<R>(R value, RuntimeException failure) {
    }

    public static <T, R> List<Outcome<R>> map(List<T> items, int limit, Function<T, R> work) {
        if (items.isEmpty()) {
            return List.of();
        }
        var permits = new Semaphore(Math.max(1, limit));
        var futures = new ArrayList<Future<R>>(items.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var item : items) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return work.apply(item);
                    } finally {
                        permits.release();
                    }
                }));
            }
            var outcomes = new ArrayList<Outcome<R>>(items.size());
            for (var future : futures) {
                outcomes.add(outcome(future));
            }
            return outcomes;
        }
    }

    private static <R> Outcome<R> outcome(Future<R> future) {
        try {
            return new Outcome<>(future.get(), null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                return new Outcome<>(null, runtime);
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a semantic decision", e);
        }
    }
}
