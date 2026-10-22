package com.rivell.workbench.workflow;

import java.util.List;

public class ReviewNotFinalizableException extends RuntimeException {

    private final List<FinalizationBlocker> blockers;

    public ReviewNotFinalizableException(List<FinalizationBlocker> blockers) {
        super("This review cannot be finalized yet. " + blockers.stream()
                .map(FinalizationBlocker::message).reduce((a, b) -> a + " " + b)
                .orElse("Check that every finding has a decision."));
        this.blockers = List.copyOf(blockers);
    }

    public List<FinalizationBlocker> blockers() {
        return blockers;
    }
}
