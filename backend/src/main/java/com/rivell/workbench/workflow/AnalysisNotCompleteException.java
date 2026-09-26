package com.rivell.workbench.workflow;

import java.util.UUID;

public class AnalysisNotCompleteException extends RuntimeException {

    public AnalysisNotCompleteException(UUID reviewId) {
        super("Review " + reviewId + " has no completed machine analysis");
    }
}
