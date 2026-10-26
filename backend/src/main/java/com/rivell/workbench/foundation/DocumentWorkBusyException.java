package com.rivell.workbench.foundation;

/**
 * Every document-work slot stayed taken for the whole wait budget. A request that gets
 * this is refused rather than queued, so waiting uploads do not hold their bytes in memory
 * indefinitely. The message is for logs; the client gets a bounded sentence and a retry hint.
 */
public class DocumentWorkBusyException extends RuntimeException {

    /** Seconds a client should wait before trying the same document again. */
    public static final int RETRY_AFTER_SECONDS = 5;

    public DocumentWorkBusyException() {
        super("Document work wait budget exhausted");
    }
}
