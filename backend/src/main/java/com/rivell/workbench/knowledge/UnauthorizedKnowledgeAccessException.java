package com.rivell.workbench.knowledge;
import java.util.UUID;

public class UnauthorizedKnowledgeAccessException extends RuntimeException {

    public UnauthorizedKnowledgeAccessException(UUID userId) {
        super("User " + userId + " is not authorized for this knowledge operation");
    }
}
