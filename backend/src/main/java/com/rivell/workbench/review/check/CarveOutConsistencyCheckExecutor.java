package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.CrossClauseConflictPayload;
import com.rivell.workbench.review.DeviationUncertainPayload;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.M7DecisionSpecs;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalType;

/**
 * Bounded cross-clause check: is the limitation-of-liability carve-out semantically
 * consistent with the indemnification clause it interacts with? Deterministic when the
 * relationship is absent; otherwise a bounded Jev classification with both clause texts.
 */
public final class CarveOutConsistencyCheckExecutor implements ReviewCheckExecutor {

    /** Uncalibrated fail-closed bands (DW-007); see LiabilityCapCheckExecutor. */
    private static final double TRUE_BAND = 0.6;
    private static final int MAX_COUNTERPART_CHARS = 6000;

    private final SemanticDecisionService decisionService;

    public CarveOutConsistencyCheckExecutor(SemanticDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.CARVE_OUT_CONSISTENCY;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        if (!context.environment().embeddingIndexMatches()
                || !context.environment().embeddingModelMatches()) {
            return CheckOutcome.degraded(context.environment().mismatchReason());
        }
        var liability = resolveSubject(context);
        if (liability == null) {
            return CheckOutcome.degraded("subject_clause_not_found");
        }
        var counterparts = context.semantics().clauses().stream()
                .filter(clause -> clause.effectivePrimaryConcept()
                                .filter(concept -> com.rivell.workbench.taxonomy.CoreConcepts
                                        .INDEMNIFICATION.equals(concept))
                                .isPresent()
                        || clause.secondaryConcepts()
                                .contains(com.rivell.workbench.taxonomy.CoreConcepts.INDEMNIFICATION))
                .toList();
        if (counterparts.isEmpty()) {
            return CheckOutcome.degraded("counterpart_clause_not_found");
        }
        var bundle = context.evidenceCache().build(request(context, liability));
        var subjectEvidence = subjectContractIds(bundle, liability.id());
        var counterpartIds = counterparts.stream().map(ClauseSemantics::id).toList();
        var counterpartBundle = context.evidenceCache().build(request(context,
                counterparts.get(0), com.rivell.workbench.taxonomy.CoreConcepts.INDEMNIFICATION));
        var counterpartEvidence = subjectContractIds(counterpartBundle, counterpartIds);

        var counterpartText = counterparts.stream().map(ClauseSemantics::contentText)
                .reduce((first, second) -> first + "\n---\n" + second).orElse("");
        var detail = new LinkedHashMap<String, Object>();
        detail.put("counterpartCount", counterparts.size());
        detail.put("counterpartTraceId", counterpartBundle.traceId());
        detail.put("sufficiency", bundle.sufficiency().name());
        if (counterpartText.length() > MAX_COUNTERPART_CHARS) {
            detail.put("uncertaintyReason", "carve_out_context_too_large");
            return boundedUncertainOutcome(context, detail, bundle,
                    union(subjectEvidence, counterpartEvidence),
                    "carve_out_context_too_large", "carve_out_context_too_large")
                    .withCitedEvidence(List.of(bundle, counterpartBundle));
        }

        var state = new M7DecisionSpecs.CarveOutConsistencyState(
                M7DecisionSpecs.CARVE_OUT_STATE_SCHEMA,
                liability.contentText(), counterpartText);
        com.rivell.workbench.intelligence.DecisionExecution execution;
        try {
            execution = decisionService.evaluate(
                    M7DecisionSpecs.carveOutConsistency(), state);
        } catch (com.rivell.workbench.intelligence.SpecStateMismatchException
                | com.rivell.workbench.intelligence.UnknownDecisionSpecException
                | com.rivell.workbench.intelligence.NoProviderConfiguredException e) {
            return CheckOutcome.failed(
                    "decision_contract_error:" + e.getClass().getSimpleName());
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            return boundedUncertainOutcome(context, detail, bundle,
                    union(subjectEvidence, counterpartEvidence),
                    "cross_clause_decision_unavailable",
                    "provider_unavailable:cross_clause_decision_unavailable")
                    .withCitedEvidence(List.of(bundle, counterpartBundle));
        }
        var answer = execution.answers().get(M7DecisionSpecs.CONSISTENCY);
        if (!(answer instanceof ChoiceAnswer choice)) {
            return CheckOutcome.failed("consistency_answer_missing");
        }
        detail.put("consistency", choice.selected());
        detail.put("confidence", choice.confidence());
        var bestProbability = choice.probabilities()
                .getOrDefault(choice.selected(), choice.confidence());
        if (bestProbability < TRUE_BAND
                && !M7DecisionSpecs.NOT_COMPARABLE_CARVE.equals(choice.selected())) {
            detail.put("uncertaintyReason", "cross_clause_classification_uncertain");
            return boundedUncertainOutcome(context, detail, bundle,
                    union(subjectEvidence, counterpartEvidence),
                    "cross_clause_classification_uncertain",
                    "cross_clause_classification_uncertain")
                    .withCitedEvidence(List.of(bundle, counterpartBundle));
        }

        if (M7DecisionSpecs.CONSISTENT.equals(choice.selected())) {
            return CheckOutcome.completed(List.of(), detail, bundle.traceId()).withCitedEvidence(List.of(bundle, counterpartBundle));
        }
        if (M7DecisionSpecs.NOT_COMPARABLE_CARVE.equals(choice.selected())) {
            return CheckOutcome.completed(List.of(signal(context,
                    SignalType.CROSS_CLAUSE_UNCERTAIN, IssueFamily.CARVE_OUT_INCONSISTENCY,
                    new DeviationUncertainPayload("carve_out_not_comparable"),
                    union(subjectEvidence, counterpartEvidence),
                    M7DecisionSpecs.CARVE_OUT_CONSISTENCY_SPEC,
                    M7DecisionSpecs.CARVE_OUT_CONSISTENCY_VERSION,
                    execution.executionId(), execution.metadata().resolvedModel(),
                    execution.metadata().usage(),
                    confidenceQuality(bundle.sufficiency().name(), choice))),
                    detail, bundle.traceId()).withCitedEvidence(List.of(bundle, counterpartBundle));
        }
        if (!M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY.equals(choice.selected())) {
            return CheckOutcome.degraded("unexpected_consistency_answer:" + choice.selected());
        }
        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, SignalType.CROSS_CLAUSE_CONFLICT,
                IssueFamily.CARVE_OUT_INCONSISTENCY,
                new CrossClauseConflictPayload(choice.selected(),
                        "liability carve-out and indemnification clause contradict each other"),
                union(subjectEvidence, counterpartEvidence),
                M7DecisionSpecs.CARVE_OUT_CONSISTENCY_SPEC,
                M7DecisionSpecs.CARVE_OUT_CONSISTENCY_VERSION, execution.executionId(),
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                confidenceQuality(bundle.sufficiency().name(), choice)));
        return CheckOutcome.completed(signals, detail, bundle.traceId()).withCitedEvidence(List.of(bundle, counterpartBundle));
    }

    private CheckOutcome boundedUncertainOutcome(
            ReviewCheckContext context, Map<String, Object> detail, EvidenceBundle bundle,
            List<UUID> evidence, String payloadReason, String degradedReason) {
        var signal = signal(context, SignalType.CROSS_CLAUSE_UNCERTAIN,
                IssueFamily.CARVE_OUT_INCONSISTENCY,
                new DeviationUncertainPayload(payloadReason), evidence, null, null, null,
                null, null, Map.of("sufficiency", bundle.sufficiency().name()));
        return CheckOutcome.degraded(degradedReason, List.of(signal), detail, bundle.traceId());
    }

    private static Map<String, Object> confidenceQuality(
            String sufficiency, ChoiceAnswer choice) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("sufficiency", sufficiency);
        quality.put("confidence", choice.confidence());
        quality.put("probabilities", choice.probabilities());
        return quality;
    }

    private ClauseSemantics resolveSubject(ReviewCheckContext context) {
        return context.semantics().clauses().stream()
                .filter(clause -> clause.headingNodeId()
                        .equals(context.check().subjectHeadingNodeId()))
                .findFirst().orElse(null);
    }

    private static EvidenceRequest request(ReviewCheckContext context, ClauseSemantics clause) {
        return request(context, clause, context.check().concept());
    }

    private static EvidenceRequest request(
            ReviewCheckContext context, ClauseSemantics clause,
            com.rivell.workbench.taxonomy.ConceptKey concept) {
        var snapshot = context.snapshot();
        return EvidenceRequest.forClauseReview(
                snapshot.organizationId(), concept,
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

    private static List<UUID> subjectContractIds(EvidenceBundle bundle, UUID clauseId) {
        return bundle.currentContractItems().stream()
                .filter(item -> clauseId.equals(item.sourceClauseId()))
                .map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> subjectContractIds(EvidenceBundle bundle, List<UUID> clauseIds) {
        return bundle.currentContractItems().stream()
                .filter(item -> clauseIds.contains(item.sourceClauseId()))
                .map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new java.util.LinkedHashSet<UUID>();
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static RawReviewSignal signal(
            ReviewCheckContext context, SignalType type, IssueFamily issueFamily,
            com.rivell.workbench.review.SignalPayload payload, List<UUID> evidenceItemIds,
            String specId, Integer specVersion, UUID executionId, String resolvedModel,
            com.rivell.workbench.intelligence.TokenUsage usage, Map<String, Object> quality) {
        var check = context.check();
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(),
                context.runId(), check.id(), type, FindingSubjectKind.CLAUSE,
                check.subjectHeadingNodeId(), null, check.concept(),
                check.secondaryConcept(), issueFamily, payload, evidenceItemIds,
                specId, specVersion, executionId, null, null, resolvedModel, usage,
                quality, type.name() + ":" + check.id(), context.executedAt());
    }
}
