package com.rivell.workbench.workflow;

import java.util.UUID;

public class FindingNotActionableException extends RuntimeException {

    public FindingNotActionableException(UUID findingId, String proposedAction) {
        super("Finding " + findingId + " has no executable recommendation ("
                + proposedAction + "); edit the change or reject the finding");
    }
}
