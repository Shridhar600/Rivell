package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalPayload;
import com.rivell.workbench.review.SignalType;

/** Builds one clause-subject signal of a policy-comparison check; each signal states its own parts. */
final class PolicySignalBuilder {

    private final ReviewCheckContext context;
    private SignalType signalType;
    private IssueFamily issueFamily;
    private SignalPayload payload;
    private List<UUID> evidence = List.of();
    private String decisionSpecId;
    private Integer decisionSpecVersion;
    private UUID jevExecutionId;
    private UUID generativeExecutionId;
    private String verificationOutcome;
    private String resolvedModel;
    private TokenUsage usage;
    private Map<String, Object> quality = Map.of();
    private String dedupeSuffix = "";

    PolicySignalBuilder(ReviewCheckContext context) {
        this.context = context;
    }

    PolicySignalBuilder signalType(SignalType value) {
        this.signalType = value;
        return this;
    }

    PolicySignalBuilder issueFamily(IssueFamily value) {
        this.issueFamily = value;
        return this;
    }

    PolicySignalBuilder payload(SignalPayload value) {
        this.payload = value;
        return this;
    }

    PolicySignalBuilder evidence(List<UUID> value) {
        this.evidence = value;
        return this;
    }

    PolicySignalBuilder decisionSpec(String id, Integer version, UUID executionId) {
        this.decisionSpecId = id;
        this.decisionSpecVersion = version;
        this.jevExecutionId = executionId;
        return this;
    }

    PolicySignalBuilder generativeExecution(UUID executionId) {
        this.generativeExecutionId = executionId;
        return this;
    }

    PolicySignalBuilder verificationOutcome(String value) {
        this.verificationOutcome = value;
        return this;
    }

    PolicySignalBuilder resolvedModel(String value) {
        this.resolvedModel = value;
        return this;
    }

    PolicySignalBuilder usage(TokenUsage value) {
        this.usage = value;
        return this;
    }

    PolicySignalBuilder quality(Map<String, Object> value) {
        this.quality = value;
        return this;
    }

    PolicySignalBuilder dedupeSuffix(String value) {
        this.dedupeSuffix = value;
        return this;
    }

    RawReviewSignal build() {
        var check = context.check();
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(),
                context.runId(), check.id(), signalType, FindingSubjectKind.CLAUSE,
                check.subjectHeadingNodeId(), null, check.concept(),
                check.secondaryConcept(), issueFamily, payload, evidence,
                decisionSpecId, decisionSpecVersion, jevExecutionId,
                generativeExecutionId, verificationOutcome, resolvedModel, usage,
                quality, signalType.name() + ":" + check.id() + ":" + dedupeSuffix,
                context.executedAt());
    }
}
