package com.rivell.workbench.knowledge;

import java.util.UUID;

/** The requested candidate concept correction names an unknown taxonomy key. */
public class InvalidConceptCorrectionException extends RuntimeException {

    public InvalidConceptCorrectionException(UUID candidateId) {
        super("That topic is not recognized. Choose a topic from the list.");
    }
}
