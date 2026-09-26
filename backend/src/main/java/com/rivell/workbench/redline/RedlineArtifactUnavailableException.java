package com.rivell.workbench.redline;

/** Download requested for an execution that has no verified, published artifact. */
public class RedlineArtifactUnavailableException extends RuntimeException {

    public RedlineArtifactUnavailableException(RedlineExecutionStatus status,
            RedlineOutcome outcome) {
        super("No verified redline artifact is available for this execution (status="
                + status + (outcome == null ? "" : ", outcome=" + outcome) + ")");
    }
}
