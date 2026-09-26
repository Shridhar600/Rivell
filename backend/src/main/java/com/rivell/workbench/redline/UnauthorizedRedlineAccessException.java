package com.rivell.workbench.redline;

import java.util.UUID;

public class UnauthorizedRedlineAccessException extends RuntimeException {

    public UnauthorizedRedlineAccessException(UUID userId) {
        super("Identity " + userId + " is not authorized to execute redlines");
    }
}
