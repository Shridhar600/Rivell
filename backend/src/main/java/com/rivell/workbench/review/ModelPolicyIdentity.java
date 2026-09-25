package com.rivell.workbench.review;

import java.util.Objects;

/** Configured model-policy identity in effect when the review was created. */
public record ModelPolicyIdentity(
        String decisionProviderId,
        String decisionModelAlias,
        String generativeProviderId,
        String generativeModelAlias) {

    public ModelPolicyIdentity {
        Objects.requireNonNull(decisionProviderId, "decisionProviderId");
        Objects.requireNonNull(decisionModelAlias, "decisionModelAlias");
        Objects.requireNonNull(generativeProviderId, "generativeProviderId");
        Objects.requireNonNull(generativeModelAlias, "generativeModelAlias");
    }
}
