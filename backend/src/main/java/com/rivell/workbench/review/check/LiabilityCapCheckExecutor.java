package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.FactSchema;
import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.EvidenceSufficiency;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.review.ApprovedDraftingPayload;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.DeviationUncertainPayload;
import com.rivell.workbench.review.EscalationPayload;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.LiabilityDeviationPayload;
import com.rivell.workbench.review.M7DecisionSpecs;
import com.rivell.workbench.review.M7ReasoningSpecs;
import com.rivell.workbench.review.OpenEndedAnalysisPayload;
import com.rivell.workbench.review.PolicyAmbiguousPayload;
import com.rivell.workbench.review.PolicyUnavailablePayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalPayload;
import com.rivell.workbench.review.SignalType;

/**
 * Limitation-of-liability clause check: authority-aware evidence against the frozen
 * pins, then a bounded Jev deviation classification. Policy missing/ambiguous/conflicted
 * is preserved, never inferred. A bounded deviation with approved drafting never calls a
 * generative model; open-ended generation runs only when the clause cannot be compared
 * with policy and no approved drafting exists, and every candidate is independently
 * verified before it becomes a signal payload.
 */
public final class LiabilityCapCheckExecutor implements ReviewCheckExecutor {

    private final EvidenceService evidenceService;
    private final SemanticDecisionService decisionService;
    private final GenerativeReasoningService reasoningService;

    public LiabilityCapCheckExecutor(
            EvidenceService evidenceService,
            SemanticDecisionService decisionService,
            GenerativeReasoningService reasoningService) {
        this.evidenceService = evidenceService;
        this.decisionService = decisionService;
        this.reasoningService = reasoningService;
    }

    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.LIABILITY_CAP;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        if (!context.environment().embeddingIndexMatches()
                || !context.environment().embeddingModelMatches()) {
            return CheckOutcome.degraded(context.environment().mismatchReason());
        }
        var clause = resolveSubject(context);
        if (clause == null) {
            return CheckOutcome.degraded("subject_clause_not_found");
        }

        var bundle = evidenceService.buildForOrganization(request(context, clause));
        var sufficiency = bundle.sufficiency();
        var subjectEvidence = subjectContractIds(bundle, clause.id());
        var detail = new LinkedHashMap<String, Object>();
        detail.put("sufficiency", sufficiency.name());

        if (sufficiency == EvidenceSufficiency.POLICY_MISSING) {
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.POLICY_UNAVAILABLE, IssueFamily.POLICY_UNAVAILABLE,
                    new PolicyUnavailablePayload(sufficiency.name()),
                    subjectEvidence, null, null, null, null, null, null, null,
                    Map.of("sufficiency", sufficiency.name()))),
                    detail, bundle.traceId());
        }
        if (sufficiency == EvidenceSufficiency.AMBIGUOUS_POLICY) {
            var ruleCount = bundle.policyItems().size();
            if (ruleCount < 2) {
                return CheckOutcome.degraded("ambiguity_without_multiple_rules");
            }
            detail.put("applicableRuleCount", ruleCount);
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.POLICY_AMBIGUOUS, IssueFamily.POLICY_AMBIGUOUS,
                    new PolicyAmbiguousPayload(ruleCount), policyIds(bundle),
                    null, null, null, null, null, null, null,
                    Map.of("sufficiency", sufficiency.name()))),
                    detail, bundle.traceId());
        }
        if (sufficiency == EvidenceSufficiency.SOURCE_CONFLICT
                || sufficiency == EvidenceSufficiency.CONTRACT_CONTEXT_INCOMPLETE) {
            detail.put("uncertaintyReason", sufficiency.name());
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.DEVIATION_UNCERTAIN, IssueFamily.LIABILITY_CAP_UNCERTAIN,
                    new DeviationUncertainPayload(sufficiency.name()),
                    union(policyIds(bundle), subjectEvidence),
                    null, null, null, null, null, null, null,
                    Map.of("sufficiency", sufficiency.name()))),
                    detail, bundle.traceId());
        }

        var facts = liabilityFacts(clause);
        var capMultiple = facts == null ? ""
                : facts.values().getOrDefault("liabilityCapMultiple", "");
        var capBasis = facts == null ? "" : facts.values().getOrDefault("capBasis", "");
        var escalationTrigger = escalationTrigger(bundle);
        var requiresApproval = bundle.policyItems().stream()
                .map(EvidenceItem::policyFacts).filter(Objects::nonNull)
                .anyMatch(policy -> Boolean.TRUE.equals(policy.requiresApproval()));
        detail.put("factsAvailable", facts != null);
        detail.put("escalationTrigger",
                escalationTrigger == null ? "" : escalationTrigger);
        detail.put("requiresApproval", requiresApproval);

        var state = new M7DecisionSpecs.LiabilityDeviationState(
                M7DecisionSpecs.LIABILITY_DEVIATION_STATE_SCHEMA,
                clause.contentText(), capMultiple, capBasis,
                facts != null && Boolean.parseBoolean(facts.values()
                        .getOrDefault("consequentialDamagesExcluded", "false")),
                firstText(bundle.policyItems()), prohibitedPosition(bundle), escalationTrigger);

        var execution = decisionService.evaluate(M7DecisionSpecs.liabilityDeviation(), state);
        var answer = execution.answers().get(M7DecisionSpecs.DEVIATION_TYPE);
        if (!(answer instanceof ChoiceAnswer choice)) {
            return CheckOutcome.degraded("deviation_answer_missing");
        }
        var selected = choice.selected();
        detail.put("deviationType", selected);
        detail.put("policyItemCount", bundle.policyItems().size());
        detail.put("draftingItemCount", bundle.approvedDraftingItems().size());

        if (M7DecisionSpecs.NOT_COMPARABLE.equals(selected)) {
            detail.put("uncertaintyReason", "policy_not_comparable");
            if (bundle.approvedDraftingItems().isEmpty() && !bundle.policyItems().isEmpty()) {
                return openEndedEscalation(context, clause, bundle, detail, sufficiency,
                        subjectEvidence);
            }
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.DEVIATION_UNCERTAIN, IssueFamily.LIABILITY_CAP_UNCERTAIN,
                    new DeviationUncertainPayload("policy_not_comparable"),
                    union(policyIds(bundle), subjectEvidence),
                    M7DecisionSpecs.LIABILITY_DEVIATION_SPEC,
                    M7DecisionSpecs.LIABILITY_DEVIATION_VERSION, execution.executionId(),
                    null, null, execution.metadata().resolvedModel(),
                    execution.metadata().usage(),
                    Map.of("sufficiency", sufficiency.name()))),
                    detail, bundle.traceId());
        }
        if (M7DecisionSpecs.NO_DEVIATION.equals(selected)) {
            return CheckOutcome.completed(List.of(), detail, bundle.traceId());
        }
        if (!M7DecisionSpecs.CAP_ABOVE_PREFERRED.equals(selected)
                && !M7DecisionSpecs.PROHIBITED_STRUCTURE.equals(selected)) {
            return CheckOutcome.degraded("unexpected_deviation_type:" + selected);
        }

        var deviationEvidence = validateEvidence(bundle,
                union(policyIds(bundle), subjectEvidence));
        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, SignalType.POLICY_DEVIATION,
                IssueFamily.LIABILITY_CAP_DEVIATION,
                new LiabilityDeviationPayload(selected, capMultiple, capBasis),
                deviationEvidence, M7DecisionSpecs.LIABILITY_DEVIATION_SPEC,
                M7DecisionSpecs.LIABILITY_DEVIATION_VERSION, execution.executionId(), null, null,
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                Map.of("sufficiency", sufficiency.name(), "deviationType", selected,
                        "factsAvailable", facts != null)));
        var drafting = bundle.approvedDraftingItems().stream().findFirst().orElse(null);
        if (drafting != null) {
            signals.add(signal(context, SignalType.APPROVED_DRAFTING_AVAILABLE,
                    IssueFamily.LIABILITY_CAP_DEVIATION,
                    new ApprovedDraftingPayload(drafting.evidenceId(), drafting.text()),
                    List.of(drafting.evidenceId()), null, null, null, null, null, null, null,
                    Map.of("draftingAvailable", true)));
        }
        if (escalationTrigger != null || requiresApproval) {
            signals.add(signal(context, SignalType.ESCALATION_REQUIRED,
                    IssueFamily.LIABILITY_CAP_DEVIATION,
                    new EscalationPayload(escalationTrigger, requiresApproval),
                    policyIds(bundle), null, null, null, null, null, null, null,
                    Map.of("requiresApproval", requiresApproval,
                            "escalationTrigger", escalationTrigger == null
                                    ? "" : escalationTrigger)));
        }
        return CheckOutcome.completed(signals, detail, bundle.traceId());
    }

    private CheckOutcome openEndedEscalation(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            Map<String, Object> detail, EvidenceSufficiency sufficiency,
            List<UUID> subjectEvidence) {
        var evidence = union(policyIds(bundle), subjectEvidence);
        try {
            var generation = reasoningService.generate(
                    M7ReasoningSpecs.openEndedLiability(),
                    new M7ReasoningSpecs.OpenEndedLiabilityState(
                            M7ReasoningSpecs.OPEN_ENDED_STATE_SCHEMA,
                            clause.contentText(), firstText(bundle.policyItems())));
            var proposed = generation.fields().get(M7ReasoningSpecs.PROPOSED_LANGUAGE);
            var explanation = generation.fields().get(M7ReasoningSpecs.EXPLANATION);
            if (proposed == null || proposed.isBlank() || explanation == null
                    || explanation.isBlank() || proposed.length() > 2000
                    || proposed.equals(clause.contentText())) {
                throw new IllegalStateException("open_ended_candidate_failed_validation");
            }
            var verification = verifyCandidate(context, clause, proposed, bundle);
            detail.put("openEnded", true);
            detail.put("verificationOutcome", verification.outcome());
            detail.put("generativeExecutionId", generation.executionId().toString());
            var signal = signal(context, SignalType.OPEN_ENDED_ANALYSIS_RESULT,
                    IssueFamily.OPEN_ENDED_LIABILITY,
                    new OpenEndedAnalysisPayload(proposed, explanation,
                            verification.outcome()),
                    evidence, M7ReasoningSpecs.OPEN_ENDED_LIABILITY_SPEC,
                    M7ReasoningSpecs.OPEN_ENDED_LIABILITY_VERSION, null,
                    generation.executionId(), verification.outcome(),
                    generation.metadata().resolvedModel(),
                    generation.metadata().usage(),
                    Map.of("sufficiency", sufficiency.name(),
                            "verificationOutcome", verification.outcome(),
                            "verificationExecutionId",
                            verification.executionId() == null
                                    ? "" : verification.executionId().toString()));
            return CheckOutcome.completed(List.of(signal), detail, bundle.traceId());
        } catch (RuntimeException e) {
            var reason = "open_ended_generation_unavailable:"
                    + e.getClass().getSimpleName();
            detail.put("uncertaintyReason", "open_ended_generation_unavailable");
            var signal = signal(context, SignalType.DEVIATION_UNCERTAIN,
                    IssueFamily.LIABILITY_CAP_UNCERTAIN,
                    new DeviationUncertainPayload("open_ended_generation_unavailable"),
                    evidence, null, null, null, null, null, null, null,
                    Map.of("sufficiency", sufficiency.name()));
            return CheckOutcome.degraded(reason, List.of(signal), detail, bundle.traceId());
        }
    }

    private Verification verifyCandidate(
            ReviewCheckContext context, ClauseSemantics clause, String proposed,
            EvidenceBundle bundle) {
        try {
            var execution = decisionService.evaluate(M7DecisionSpecs.findingVerification(),
                    new M7DecisionSpecs.FindingVerificationState(
                            M7DecisionSpecs.FINDING_VERIFICATION_STATE_SCHEMA,
                            proposed, firstText(bundle.policyItems()), clause.contentText()));
            var satisfies = booleanAnswer(
                    execution.answers().get(M7DecisionSpecs.SATISFIES_POLICY));
            var newRisk = booleanAnswer(
                    execution.answers().get(M7DecisionSpecs.INTRODUCED_NEW_RISK));
            var outcome = newRisk ? "DISPUTED" : satisfies ? "VERIFIED" : "UNCERTAIN";
            return new Verification(outcome, execution.executionId());
        } catch (RuntimeException e) {
            return new Verification("UNCERTAIN", null);
        }
    }

    private static boolean booleanAnswer(SemanticAnswer answer) {
        return answer instanceof YesNoAnswer yesNo && yesNo.probability() >= 0.5;
    }

    private record Verification(String outcome, UUID executionId) {
    }

    private ClauseSemantics resolveSubject(ReviewCheckContext context) {
        return context.semantics().clauses().stream()
                .filter(clause -> clause.headingNodeId()
                        .equals(context.check().subjectHeadingNodeId()))
                .findFirst().orElse(null);
    }

    private static LegalFact liabilityFacts(ClauseSemantics clause) {
        return clause.effectiveFacts().stream()
                .filter(fact -> FactSchema.LIABILITY_FACTS.id().equals(fact.factSchema()))
                .findFirst().orElse(null);
    }

    private static EvidenceRequest request(ReviewCheckContext context, ClauseSemantics clause) {
        var snapshot = context.snapshot();
        return EvidenceRequest.forClauseReview(
                snapshot.organizationId(), context.check().concept(),
                snapshot.contractId(), snapshot.contractVersionNumber(), clause.id(),
                clause.contentText(),
                new com.rivell.workbench.contracts.ReviewProfile(
                        snapshot.contractType(), snapshot.ourSideRole(),
                        snapshot.paperSource(), snapshot.jurisdiction(),
                        snapshot.contractPackKey()),
                snapshot.effectiveOn(),
                snapshot.knowledgePinNumber() == 0L
                        ? KnowledgePin.none()
                        : KnowledgePin.at(snapshot.knowledgePinNumber()));
    }

    private static String firstText(List<EvidenceItem> items) {
        return items.stream().map(EvidenceItem::text).findFirst().orElse("");
    }

    private static String prohibitedPosition(EvidenceBundle bundle) {
        return bundle.policyItems().stream()
                .filter(item -> item.policyFacts() != null
                        && item.policyFacts().positions().stream().anyMatch(role ->
                                role == com.rivell.workbench.knowledge.PositionRole.PROHIBITED))
                .map(EvidenceItem::text).findFirst().orElse(null);
    }

    private static String escalationTrigger(EvidenceBundle bundle) {
        return bundle.policyItems().stream()
                .map(EvidenceItem::policyFacts).filter(Objects::nonNull)
                .map(com.rivell.workbench.retrieval.EvidenceItem.PolicyFacts::escalationTrigger)
                .filter(trigger -> trigger != null && !trigger.isBlank())
                .findFirst().orElse(null);
    }

    private static List<UUID> policyIds(EvidenceBundle bundle) {
        return bundle.policyItems().stream().map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> subjectContractIds(EvidenceBundle bundle, UUID clauseId) {
        return bundle.currentContractItems().stream()
                .filter(item -> clauseId.equals(item.sourceClauseId()))
                .map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new java.util.LinkedHashSet<UUID>();
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static List<UUID> validateEvidence(EvidenceBundle bundle, List<UUID> ids) {
        var available = bundle.items().stream().map(EvidenceItem::evidenceId).toList();
        if (!available.containsAll(ids)) {
            throw new IllegalStateException("evidence references not present in the bundle");
        }
        return ids;
    }

    private static RawReviewSignal signal(
            ReviewCheckContext context, SignalType type, IssueFamily issueFamily,
            SignalPayload payload, List<UUID> evidenceItemIds, String specId,
            Integer specVersion, UUID jevExecutionId, UUID generativeExecutionId,
            String verificationOutcome, String resolvedModel,
            com.rivell.workbench.intelligence.TokenUsage usage,
            Map<String, Object> quality) {
        var check = context.check();
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(),
                context.runId(), check.id(), type, FindingSubjectKind.CLAUSE,
                check.subjectHeadingNodeId(), null, check.concept(),
                check.secondaryConcept(), issueFamily, payload, evidenceItemIds,
                specId, specVersion, jevExecutionId, generativeExecutionId,
                verificationOutcome, resolvedModel, usage, quality,
                type.name() + ":" + check.id(), context.executedAt());
    }
}
