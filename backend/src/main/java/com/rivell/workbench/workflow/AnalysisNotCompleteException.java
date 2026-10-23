package com.rivell.workbench.workflow;

import java.util.UUID;

public class AnalysisNotCompleteException extends RuntimeException {

    public AnalysisNotCompleteException(UUID reviewId) {
        super("The analysis for this review has not finished yet.");
    }
}
