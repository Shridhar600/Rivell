package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderFailuresTest {

    @Test
    void rethrowIfOutOfCreditKeepsTheBillingFailure() {
        var failure = new ProviderOutOfCreditException("Provider account has no credits");

        assertThatThrownBy(() -> ProviderFailures.rethrowIfOutOfCredit(failure))
                .isSameAs(failure);
    }

    @Test
    void rethrowIfOutOfCreditLeavesOrdinaryFailuresAlone() {
        ProviderFailures.rethrowIfOutOfCredit(new ProviderUnavailableException("down"));
        ProviderFailures.rethrowIfOutOfCredit(null);
    }

    @Test
    void theReviewerReasonNamesOutOfCredit() {
        assertThat(ProviderFailures.degradedReason(
                new ProviderOutOfCreditException("Provider account has no credits")))
                .isEqualTo("provider_out_of_credit");
    }

    @Test
    void theReviewerReasonNamesOtherFailuresByType() {
        assertThat(ProviderFailures.degradedReason(
                new ProviderRateLimitedException("slow down", null, 429)))
                .isEqualTo("provider_unavailable:ProviderRateLimitedException");
    }
}
