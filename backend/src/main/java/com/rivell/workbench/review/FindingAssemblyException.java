package com.rivell.workbench.review;

/**
 * Deterministic assembly contract violation (e.g. a clause-subject signal without
 * evidence). Failing loudly is honest: the generation becomes FAILED with the reason
 * instead of producing groundless findings.
 */
public class FindingAssemblyException extends RuntimeException {

    public FindingAssemblyException(String message) {
        super(message);
    }
}
