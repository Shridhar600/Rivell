package com.rivell.workbench.workflow;

import java.util.List;

public class ReviewNotFinalizableException extends RuntimeException {

    private final List<FinalizationBlocker> blockers;

    public ReviewNotFinalizableException(List<FinalizationBlocker> blockers) {
        super("The review cannot be finalized: " + blockers.stream()
                .map(blocker -> blocker.code().name()).reduce((a, b) -> a + ", " + b)
                .orElse("unknown blocker"));
        this.blockers = List.copyOf(blockers);
    }

    public List<FinalizationBlocker> blockers() {
        return blockers;
    }
}
