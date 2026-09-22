package com.redstar.workbench.contracts;

import java.util.NoSuchElementException;
import java.util.UUID;

public class ContractNotFoundException extends NoSuchElementException {

    public ContractNotFoundException(UUID contractId) {
        super("Contract not found: " + contractId);
    }
}
