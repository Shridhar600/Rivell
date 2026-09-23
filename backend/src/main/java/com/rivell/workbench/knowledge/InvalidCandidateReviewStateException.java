package com.rivell.workbench.knowledge;

public class InvalidCandidateReviewStateException extends RuntimeException {

    public InvalidCandidateReviewStateException(KnowledgeCandidate candidate, String operation) {
        super("Candidate " + candidate.id() + " in state " + candidate.reviewStatus()
                + " cannot be " + operation);
    }
}
