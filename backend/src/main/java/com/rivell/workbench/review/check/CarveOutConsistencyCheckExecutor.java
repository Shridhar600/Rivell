package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
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

    private final EvidenceService evidenceService;
    private final SemanticDecisionService decisionService;

    public CarveOutConsistencyCheckExecutor(
            EvidenceService evidenceService, SemanticDecisionService decisionService) {
        this.evidenceService = evidenceService;
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
                                .filter(concept -> concept == LegalConcept.INDEMNIFICATION)
                                .isPresent()
                        || clause.secondaryConcepts().contains(LegalConcept.INDEMNIFICATION))
                .toList();
        if (counterparts.isEmpty()) {
            return CheckOutcome.degraded("counterpart_clause_not_found");
        }
        var bundle = evidenceService.buildForOrganization(request(context, liability));
        var subjectEvidence = subjectContractIds(bundle, liability.id());
        var counterpartIds = counterparts.stream().map(ClauseSemantics::id).toList();
        var counterpartEvidence = subjectContractIds(bundle, counterpartIds);

        var state = new M7DecisionSpecs.CarveOutConsistencyState(
                M7DecisionSpecs.CARVE_OUT_STATE_SCHEMA,
                liability.contentText(),
                counterparts.stream().map(ClauseSemantics::contentText)
                        .reduce((first, second) -> first + "\n---\n" + second).orElse(""));
        var execution = decisionService.evaluate(
                M7DecisionSpecs.carveOutConsistency(), state);
        var answer = execution.answers().get(M7DecisionSpecs.CONSISTENCY);
        if (!(answer instanceof ChoiceAnswer choice)) {
            return CheckOutcome.degraded("consistency_answer_missing");
        }

        var detail = new LinkedHashMap<String, Object>();
        detail.put("consistency", choice.selected());
        detail.put("counterpartCount", counterparts.size());
        detail.put("sufficiency", bundle.sufficiency().name());

        if (M7DecisionSpecs.CONSISTENT.equals(choice.selected())) {
            return CheckOutcome.completed(List.of(), detail, bundle.traceId());
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
                    Map.of("sufficiency", bundle.sufficiency().name()))),
                    detail, bundle.traceId());
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
                Map.of("sufficiency", bundle.sufficiency().name())));
        return CheckOutcome.completed(signals, detail, bundle.traceId());
    }

    private ClauseSemantics resolveSubject(ReviewCheckContext context) {
        return context.semantics().clauses().stream()
                .filter(clause -> clause.headingNodeId()
                        .equals(context.check().subjectHeadingNodeId()))
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
