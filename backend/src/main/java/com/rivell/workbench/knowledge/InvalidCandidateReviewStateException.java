package com.rivell.workbench.knowledge;

public class InvalidCandidateReviewStateException extends RuntimeException {

    public InvalidCandidateReviewStateException(KnowledgeCandidate candidate, String operation) {
        super("This entry is " + describe(candidate.reviewStatus()) + ", so it cannot be "
                + operation + ".");
    }

    /**
     * The candidate held the expected state when it was read and had moved by the time it was
     * written. Reported separately from an illegal transition because the state the caller read is
     * not the state the candidate is in, and quoting the stale state as if it were current would
     * read as a contradiction.
     */
    public static InvalidCandidateReviewStateException lostConcurrentTransition(
            KnowledgeCandidate candidate, String operation) {
        return new InvalidCandidateReviewStateException("Someone else changed this entry while it "
                + "was being " + operation + ". Reload it and try again.");
    }

    private InvalidCandidateReviewStateException(String message) {
        super(message);
    }

    /** The state as a reviewer reads it, not as the enum is named. */
    static String describe(ReviewStatus status) {
        return switch (status) {
            case PENDING -> "waiting for review";
            case APPROVED -> "approved";
            case EDITED -> "edited";
            case REJECTED -> "rejected";
            case PUBLISHED -> "published";
        };
    }
}
