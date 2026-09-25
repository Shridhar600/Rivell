package com.rivell.workbench.review;

import java.util.UUID;

/**
 * A persisted finding references evidence that cannot be resolved from its own run.
 * This is an internal integrity failure; the API must never silently drop the link.
 */
public class ReviewEvidenceIntegrityException extends RuntimeException {

    public ReviewEvidenceIntegrityException(UUID findingId) {
        super("finding evidence could not be resolved for finding " + findingId);
    }
}
