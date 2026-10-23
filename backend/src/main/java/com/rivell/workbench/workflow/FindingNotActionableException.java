package com.rivell.workbench.workflow;

import java.util.UUID;

public class FindingNotActionableException extends RuntimeException {

    public FindingNotActionableException(UUID findingId, String proposedAction) {
        super("Rivell has no ready-made wording for this finding. Edit the wording yourself, "
                + "or reject the finding.");
    }
}
