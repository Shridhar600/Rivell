package com.rivell.workbench.review;

import java.util.UUID;

/**
 * A client-supplied idempotency key was replayed against a different contract version than the one it
 * was first used for.
 *
 * <p>The key identifies one operation. Returning the other request's review would be a confused
 * deputy: the caller would receive a valid, fully populated review of a contract it did not ask about
 * and poll the wrong one. Refusing is the only safe answer.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(UUID clientRequestId) {
        super("The supplied request id was already used for a different contract version: "
                + clientRequestId);
    }
}
