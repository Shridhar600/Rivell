package com.rivell.workbench.intelligence;

/**
 * One rule for the controlled failure model: a billing failure is never a per-clause verdict.
 * Check-level callers rethrow it so the run records exactly one reviewer-facing
 * {@code provider_out_of_credit} degradation, while callers that cannot propagate it (embedding
 * prefetch, index maintenance) still recognise it through {@link #isOutOfCredit(Throwable)} and
 * degrade their own lane.
 */
public final class ProviderFailures {

    public static final String OUT_OF_CREDIT_REASON = "provider_out_of_credit";

    private ProviderFailures() {
    }

    /**
     * Rethrows a billing failure unchanged. Used at every catch site that would otherwise turn
     * it into a bounded per-clause reason such as {@code provider_unavailable}.
     */
    public static void rethrowIfOutOfCredit(Throwable failure) {
        if (failure instanceof ProviderOutOfCreditException outOfCredit) {
            throw outOfCredit;
        }
    }

    public static boolean isOutOfCredit(Throwable failure) {
        return failure instanceof ProviderOutOfCreditException;
    }

    /** The reviewer-facing degradation reason for a provider failure. */
    public static String degradedReason(SemanticDecisionException failure) {
        return isOutOfCredit(failure)
                ? OUT_OF_CREDIT_REASON
                : "provider_unavailable:" + failure.getClass().getSimpleName();
    }
}
