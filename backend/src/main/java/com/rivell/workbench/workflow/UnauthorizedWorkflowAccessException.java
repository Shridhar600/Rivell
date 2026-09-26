package com.rivell.workbench.workflow;

import java.util.UUID;

public class UnauthorizedWorkflowAccessException extends RuntimeException {

    public UnauthorizedWorkflowAccessException(UUID userId) {
        super("Identity " + userId + " is not authorized for the human review workflow");
    }
}
