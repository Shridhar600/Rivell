package com.rivell.workbench.knowledge;

/**
 * Lifecycle of a knowledge artifact during ingestion. Synchronous today; {@code RECEIVED}
 * and {@code EXTRACTING} are reserved for the future asynchronous path.
 */
public enum IngestStatus {
    RECEIVED,
    EXTRACTING,
    EXTRACTED,
    FAILED
}
