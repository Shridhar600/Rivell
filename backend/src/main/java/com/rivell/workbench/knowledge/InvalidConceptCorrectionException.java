package com.rivell.workbench.knowledge;

import java.util.UUID;

/** The requested candidate concept correction names an unknown LegalConcept. */
public class InvalidConceptCorrectionException extends RuntimeException {

    public InvalidConceptCorrectionException(UUID candidateId) {
        super("Unknown LegalConcept in candidate concept correction: " + candidateId);
    }
}
