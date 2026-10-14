package com.rivell.workbench.redline;

import java.util.UUID;

/**
 * The verified redline artifact could not be written to document storage.
 *
 * <p>Typed so the HTTP layer can report it with the project's standard error envelope instead of an
 * untyped 500. Storage is infrastructure the operator can restore, so this is reported as
 * unavailable rather than as a client or state conflict.
 */
public class RedlineArtifactStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RedlineArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
