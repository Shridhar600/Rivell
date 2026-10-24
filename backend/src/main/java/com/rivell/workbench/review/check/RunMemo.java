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
            return new FutureTask<>(compute::get);
        });
        if (inserted.get()) {
            // The order entry is written only after computeIfAbsent has installed the mapping.
            // Written inside the mapping function, another thread could evict the run before it
            // existed in the map: the remove found nothing and the run stayed past capacity
            // without an order entry (DW-067). One lock over the deque keeps concurrent
            // insertions from each evicting a run and taking the memo below capacity.
            synchronized (order) {
                order.addLast(runId);
                while (order.size() > capacity) {
                    var oldest = order.pollFirst();
                    if (oldest != null) {
                        runs.remove(oldest);
                    }
                }
            }
        }
        task.run(); // no-op when another check already ran or is running it
        return task.get();
    }
}
