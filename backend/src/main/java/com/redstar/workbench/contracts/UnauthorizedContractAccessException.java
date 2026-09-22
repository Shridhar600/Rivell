package com.redstar.workbench.contracts;

import java.util.UUID;

public class UnauthorizedContractAccessException extends RuntimeException {

    public UnauthorizedContractAccessException(UUID userId) {
        super("User is not authorized to upload contracts: " + userId);
    }
}
