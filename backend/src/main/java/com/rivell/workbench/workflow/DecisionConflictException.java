package com.rivell.workbench.workflow;

import java.util.UUID;

public class DecisionConflictException extends RuntimeException {

    public DecisionConflictException(UUID findingId, int expectedVersion, int currentVersion) {
        super("Finding " + findingId + " decision expected version " + expectedVersion
                + " but the current revision is " + currentVersion);
    }
}
