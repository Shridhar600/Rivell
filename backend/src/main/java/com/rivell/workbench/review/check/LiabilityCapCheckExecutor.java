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
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.EvidenceSufficiency;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.review.ApprovedDraftingPayload;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.EscalationPayload;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.LiabilityDeviationPayload;
import com.rivell.workbench.review.M7DecisionSpecs;
import com.rivell.workbench.review.PolicyAmbiguousPayload;
import com.rivell.workbench.review.PolicyUnavailablePayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalPayload;
import com.rivell.workbench.review.SignalType;

/**
 * Limitation-of-liability clause check: authority-aware evidence against the frozen
 * pins, then a bounded Jev deviation classification. Policy missing/ambiguous is
 * preserved, never inferred; a bounded deviation with approved drafting never calls a
 * generative model.
 */
public final class LiabilityCapCheckExecutor implements ReviewCheckExecutor {

    private final EvidenceService evidenceService;
    private final SemanticDecisionService decisionService;

    public LiabilityCapCheckExecutor(
            EvidenceService evidenceService, SemanticDecisionService decisionService) {
        this.evidenceService = evidenceService;
        this.decisionService = decisionService;
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
        var facts = liabilityFacts(clause);
        if (facts == null) {
            return CheckOutcome.degraded("liability_facts_unavailable");
        }

        var bundle = evidenceService.buildForOrganization(request(context, clause));
        var sufficiency = bundle.sufficiency();
        if (sufficiency == EvidenceSufficiency.POLICY_MISSING) {
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.POLICY_UNAVAILABLE, IssueFamily.POLICY_UNAVAILABLE,
                    new PolicyUnavailablePayload(sufficiency.name()),
                    currentContractIds(bundle), null, null, null,
                    Map.of("sufficiency", sufficiency.name()))),
                    Map.of("sufficiency", sufficiency.name()), bundle.traceId());
        }
        if (sufficiency == EvidenceSufficiency.AMBIGUOUS_POLICY) {
            var ambiguous = signal(context, SignalType.POLICY_AMBIGUOUS,
                    IssueFamily.POLICY_AMBIGUOUS,
                    new PolicyAmbiguousPayload(Math.max(2, bundle.policyItems().size())),
                    policyIds(bundle), null, null, null,
                    Map.of("sufficiency", sufficiency.name()));
            return CheckOutcome.completed(List.of(ambiguous),
                    Map.of("sufficiency", sufficiency.name()), bundle.traceId());
        }

        var capMultiple = facts.values().getOrDefault("liabilityCapMultiple", "");
        var capBasis = facts.values().getOrDefault("capBasis", "");
        var escalationTrigger = escalationTrigger(bundle);
        var requiresApproval = bundle.policyItems().stream()
                .map(EvidenceItem::policyFacts).filter(Objects::nonNull)
                .anyMatch(policy -> Boolean.TRUE.equals(policy.requiresApproval()));
        var state = new M7DecisionSpecs.LiabilityDeviationState(
                M7DecisionSpecs.LIABILITY_DEVIATION_STATE_SCHEMA,
                clause.contentText(), capMultiple, capBasis,
                Boolean.parseBoolean(facts.values()
                        .getOrDefault("consequentialDamagesExcluded", "false")),
                preferredPosition(bundle), prohibitedPosition(bundle), escalationTrigger);

        var execution = decisionService.evaluate(M7DecisionSpecs.liabilityDeviation(), state);
        var answer = execution.answers().get(M7DecisionSpecs.DEVIATION_TYPE);
        if (!(answer instanceof ChoiceAnswer choice)) {
            return CheckOutcome.degraded("deviation_answer_missing");
        }
        var selected = choice.selected();
        var detail = new LinkedHashMap<String, Object>();
        detail.put("deviationType", selected);
        detail.put("sufficiency", sufficiency.name());
        detail.put("policyItemCount", bundle.policyItems().size());
        detail.put("draftingItemCount", bundle.approvedDraftingItems().size());

        if (M7DecisionSpecs.NOT_COMPARABLE.equals(selected)) {
            return CheckOutcome.degraded("policy_not_comparable");
        }
        if (M7DecisionSpecs.NO_DEVIATION.equals(selected)) {
            return CheckOutcome.completed(List.of(), detail, bundle.traceId());
        }
        if (!M7DecisionSpecs.CAP_ABOVE_PREFERRED.equals(selected)
                && !M7DecisionSpecs.PROHIBITED_STRUCTURE.equals(selected)) {
            return CheckOutcome.degraded("unexpected_deviation_type:" + selected);
        }

        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, SignalType.POLICY_DEVIATION,
                IssueFamily.LIABILITY_CAP_DEVIATION,
                new LiabilityDeviationPayload(selected, capMultiple, capBasis),
                union(policyIds(bundle), currentContractIds(bundle)),
                M7DecisionSpecs.LIABILITY_DEVIATION_SPEC,
                M7DecisionSpecs.LIABILITY_DEVIATION_VERSION, execution.executionId(),
                Map.of("sufficiency", sufficiency.name(), "deviationType", selected)));
        var drafting = bundle.approvedDraftingItems().stream().findFirst().orElse(null);
        if (drafting != null) {
            signals.add(signal(context, SignalType.APPROVED_DRAFTING_AVAILABLE,
                    IssueFamily.LIABILITY_CAP_DEVIATION,
                    new ApprovedDraftingPayload(drafting.evidenceId(), drafting.text()),
                    List.of(drafting.evidenceId()), null, null, null,
                    Map.of("reviewRequired", false)));
        }
        if (escalationTrigger != null || requiresApproval) {
            signals.add(signal(context, SignalType.ESCALATION_REQUIRED,
                    IssueFamily.LIABILITY_CAP_DEVIATION,
                    new EscalationPayload(escalationTrigger, requiresApproval),
                    policyIds(bundle), null, null, null,
                    Map.of("requiresApproval", requiresApproval)));
        }
        return CheckOutcome.completed(signals, detail, bundle.traceId());
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

    private static String preferredPosition(EvidenceBundle bundle) {
        return bundle.policyItems().stream().map(EvidenceItem::text).findFirst().orElse("");
    }

    private static String prohibitedPosition(EvidenceBundle bundle) {
        return bundle.policyItems().stream()
                .filter(item -> item.policyFacts() != null
                        && item.policyFacts().positions().stream()
                                .anyMatch(role -> role == com.rivell.workbench.knowledge.PositionRole.PROHIBITED))
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

    private static List<UUID> currentContractIds(EvidenceBundle bundle) {
        return bundle.currentContractItems().stream().map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new java.util.LinkedHashSet<UUID>();
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static RawReviewSignal signal(
            ReviewCheckContext context, SignalType type, IssueFamily issueFamily,
            SignalPayload payload, List<UUID> evidenceItemIds, String specId,
            Integer specVersion, UUID executionId, Map<String, Object> quality) {
        var check = context.check();
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(),
                context.runId(), check.id(), type, FindingSubjectKind.CLAUSE,
                check.subjectHeadingNodeId(), null, check.concept(),
                check.secondaryConcept(), issueFamily, payload, evidenceItemIds,
                specId, specVersion, executionId, null, null, quality,
                type.name() + ":" + check.concept() + ":" + check.subjectHeadingNodeId(),
                context.executedAt());
    }
}
