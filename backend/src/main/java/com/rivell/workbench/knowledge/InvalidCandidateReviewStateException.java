package com.rivell.workbench.knowledge;

public class InvalidCandidateReviewStateException extends RuntimeException {

    public InvalidCandidateReviewStateException(KnowledgeCandidate candidate, String operation) {
        super("Candidate " + candidate.id() + " in state " + candidate.reviewStatus()
                + " cannot be " + operation);
    }

    /**
     * The candidate held the expected state when it was read and had moved by the time it was
     * written. Reported separately from an illegal transition because the state the caller read is
     * not the state the candidate is in, and quoting the stale state as if it were current would
     * read as a contradiction.
     */
    public static InvalidCandidateReviewStateException lostConcurrentTransition(
            KnowledgeCandidate candidate, String operation) {
        return new InvalidCandidateReviewStateException("Candidate " + candidate.id()
                + " changed state while it was being " + operation
                + " and was no longer in state " + candidate.reviewStatus());
    }

    private InvalidCandidateReviewStateException(String message) {
        super(message);
    }
}
