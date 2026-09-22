package com.rivell.workbench.contracts;

import java.util.NoSuchElementException;
import java.util.UUID;

public class ContractVersionNotFoundException extends NoSuchElementException {

    public ContractVersionNotFoundException(UUID contractId, int versionNumber) {
        super("Contract version not found: " + contractId + "/" + versionNumber);
    }
}
