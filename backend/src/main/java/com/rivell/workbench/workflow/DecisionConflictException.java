package com.rivell.workbench.workflow;

import java.util.UUID;

public class DecisionConflictException extends RuntimeException {

    public DecisionConflictException(UUID findingId, int expectedVersion, int currentVersion) {
        super("Someone else has already decided this finding. Reload the review to see "
                + "their decision.");
    }
}
