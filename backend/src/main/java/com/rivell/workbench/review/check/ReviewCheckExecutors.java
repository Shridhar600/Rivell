package com.rivell.workbench.review.check;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rivell.workbench.review.CheckMechanism;

/** Mechanism registry; a missing executor is a pipeline gap, not a crash. */
public final class ReviewCheckExecutors {

    private final Map<CheckMechanism, ReviewCheckExecutor> byMechanism;

    public ReviewCheckExecutors(List<ReviewCheckExecutor> executors) {
        var map = new EnumMap<CheckMechanism, ReviewCheckExecutor>(CheckMechanism.class);
        for (var executor : executors) {
            var previous = map.put(executor.mechanism(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate executor for mechanism " + executor.mechanism());
            }
        }
        this.byMechanism = Map.copyOf(map);
    }

    public Optional<ReviewCheckExecutor> find(CheckMechanism mechanism) {
        return Optional.ofNullable(byMechanism.get(mechanism));
    }
}
