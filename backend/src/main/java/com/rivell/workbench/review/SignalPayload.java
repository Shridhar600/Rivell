package com.rivell.workbench.review;

/** Typed observation payload of a raw signal; never free model prose. */
public sealed interface SignalPayload permits PresencePayload, PolicyUnavailablePayload,
        PolicyAmbiguousPayload, LiabilityDeviationPayload, ApprovedDraftingPayload,
        EscalationPayload {

    String kind();
}
