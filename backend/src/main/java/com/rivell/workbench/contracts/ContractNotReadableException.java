package com.rivell.workbench.contracts;

/**
 * A contract version whose stored file could not be read has no parsed structure, so it cannot
 * be analysed. Distinct from a generic failure so the API can answer with a definite status.
 */
public class ContractNotReadableException extends RuntimeException {

    public ContractNotReadableException() {
        super("The contract file could not be read, so it cannot be analysed.");
    }
}
