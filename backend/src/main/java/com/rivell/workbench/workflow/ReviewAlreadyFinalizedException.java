package com.rivell.workbench.workflow;

import java.util.UUID;

public class ReviewAlreadyFinalizedException extends RuntimeException {

    public ReviewAlreadyFinalizedException(UUID reviewId) {
        super("This review is already finalized. Its decisions can no longer be changed.");
    }
}
