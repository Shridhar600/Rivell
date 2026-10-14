package com.rivell.workbench.review.check;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At most {@code max} second opinions per analysis run (spec §6). Checks of one run can execute in
 * parallel, so the count is atomic. Only the latest runs are remembered.
 */
final class AdjudicationBudget {

    private static final int MAX_RUNS = 16;

    private final int max;
    private final Map<UUID, AtomicInteger> used = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> order = new ConcurrentLinkedDeque<>();

    AdjudicationBudget(int max) {
        this.max = max;
    }

    /** True when the run may ask one more second opinion; the answer counts against the budget. */
    boolean take(UUID runId) {
        var counter = used.get(runId);
        if (counter == null) {
            var fresh = new AtomicInteger();
            counter = used.putIfAbsent(runId, fresh);
            if (counter == null) {
                counter = fresh;
                order.addLast(runId);
                while (order.size() > MAX_RUNS) {
                    var oldest = order.pollFirst();
                    if (oldest != null) {
                        used.remove(oldest);
                    }
                }
            }
        }
        return counter.incrementAndGet() <= max;
    }
}
