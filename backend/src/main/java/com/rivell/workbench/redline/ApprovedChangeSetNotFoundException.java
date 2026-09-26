package com.rivell.workbench.redline;

import java.util.UUID;

public class ApprovedChangeSetNotFoundException extends RuntimeException {

    public ApprovedChangeSetNotFoundException(UUID reviewId) {
        super("Approved change set not found for review " + reviewId);
    }
}
