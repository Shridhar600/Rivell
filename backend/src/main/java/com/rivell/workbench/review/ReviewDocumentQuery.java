package com.rivell.workbench.review;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the uploaded document behind a pinned contract version.
 *
 * <p>Keyed by the version id a review froze at creation, never by contract id alone, so a
 * later version of the same contract cannot change what an existing review reports.
 */
public interface ReviewDocumentQuery {

    Optional<PinnedDocument> find(UUID organizationId, UUID contractVersionId);
}