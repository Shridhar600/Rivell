package com.rivell.workbench.review;

import java.time.Instant;

/**
 * The immutable uploaded document behind one pinned contract version: the filename the
 * reviewer uploaded, when that version was ingested, and the size of the exact source
 * bytes it was built from.
 *
 * <p>{@code uploadedAt} is the pinned version's own ingestion time. It is deliberately not
 * the review's creation time and not the contract's latest version's time: relabelling
 * either would misdate the document the review actually reads.
 *
 * <p>{@code sizeBytes} describes the uploaded source artifact, not a derived structure or a
 * rendered redline. The source artifact is immutable, so the size a review reports stays
 * true for the life of that version.
 */
public record PinnedDocument(String filename, Instant uploadedAt, long sizeBytes) {
}