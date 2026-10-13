package com.rivell.workbench.review.check;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Remembers one computed value per analysis run: the first caller computes it, callers that ask
 * while it runs wait for the same computation, and only the latest {@code capacity} runs are kept.
 * Eviction happens after the new entry is installed, never inside the mapping function:
 * ConcurrentHashMap forbids a mapping function from updating other mappings.
 */
final class RunMemo<V> {

    private final int capacity;
    private final Map<UUID, FutureTask<V>> runs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> order = new ConcurrentLinkedDeque<>();

    RunMemo(int capacity) {
        this.capacity = capacity;
    }

    V get(UUID runId, Supplier<V> compute) throws InterruptedException, ExecutionException {
        var inserted = new AtomicBoolean();
        var task = runs.computeIfAbsent(runId, key -> {
            inserted.set(true);
            order.addLast(key);
            return new FutureTask<>(compute::get);
        });
        if (inserted.get()) {
            while (order.size() > capacity) {
                var oldest = order.pollFirst();
                if (oldest != null) {
                    runs.remove(oldest);
                }
            }
        }
        task.run(); // no-op when another check already ran or is running it
        return task.get();
    }
}
