package com.rivell.workbench.knowledge;

import java.util.UUID;

public class InvalidPublicationException extends RuntimeException {

    public InvalidPublicationException(UUID candidateId, String reason) {
        super("Candidate " + candidateId + " cannot be published: " + reason);
    }
}
