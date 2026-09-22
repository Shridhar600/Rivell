package com.rivell.workbench.knowledge;

public class UnauthorizedKnowledgeAccessException extends RuntimeException {

    public UnauthorizedKnowledgeAccessException(java.util.UUID userId) {
        super("User " + userId + " is not authorized for this knowledge operation");
    }
}
