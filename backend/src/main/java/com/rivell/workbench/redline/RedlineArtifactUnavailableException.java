package com.rivell.workbench.redline;

/** Download requested for an execution that has no verified, published artifact. */
public class RedlineArtifactUnavailableException extends RuntimeException {

    public RedlineArtifactUnavailableException(RedlineExecutionStatus status,
            RedlineOutcome outcome) {
        super(outcome == RedlineOutcome.NO_CHANGES
                ? "This review has no changes, so there is no redline file to download."
                : status == RedlineExecutionStatus.FAILED
                        ? "The redline failed, so there is no file to download. Generate it again."
                        : "There is no redline file to download for this review.");
    }
}
