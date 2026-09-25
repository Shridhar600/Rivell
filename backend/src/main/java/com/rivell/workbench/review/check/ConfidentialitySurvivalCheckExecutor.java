package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.RelationshipType;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.CrossClauseConflictPayload;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalType;

/**
 * Deterministic survival-coverage check: the confidentiality obligation must be
 * covered by survival semantics (M5 SURVIVES relationship or the retrieval lane's
 * survival evidence). Absence produces a cross-clause conflict signal; no model call.
 */
public final class ConfidentialitySurvivalCheckExecutor implements ReviewCheckExecutor {


    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.CONFIDENTIALITY_SURVIVAL;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        var clause = resolveSubject(context);
        if (clause == null) {
            return CheckOutcome.degraded("subject_clause_not_found");
        }
        var bundle = context.evidenceCache().build(request(context, clause));

        // Coverage is assessed for the concept, not one classified clause: a
        // carve-out or reference clause can also carry the concept, and the human
        // issue is whether the confidentiality concept is covered by survival
        // semantics at all (per-clause obligation granularity recorded as a limit).
        var conceptHeadingIds = context.semantics().clauses().stream()
                .filter(candidate -> candidate.effectivePrimaryConcept()
                                .filter(concept -> concept == context.check().concept())
                                .isPresent()
                        || candidate.secondaryConcepts().contains(context.check().concept()))
                .map(ClauseSemantics::headingNodeId)
                .collect(java.util.stream.Collectors.toSet());
        var coveredByRelationship = context.semantics().relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.SURVIVES)
                .anyMatch(relationship -> conceptHeadingIds.contains(
                                relationship.sourceRegionNodeId())
                        || conceptHeadingIds.contains(relationship.targetRegionNodeId()));
        var coveredByEvidence = bundle.currentContractItems().stream()
                .anyMatch(item -> item.retrievalReason() != null
                        && item.retrievalReason().contains("SURVIVES"));
        var covered = coveredByRelationship || coveredByEvidence;

        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("covered", covered);
        detail.put("basis", coveredByRelationship ? "relationship"
                : coveredByEvidence ? "evidence" : "none");
        detail.put("sufficiency", bundle.sufficiency().name());

        if (covered) {
            return CheckOutcome.completed(List.of(), detail, bundle.traceId());
        }
        var counterparts = context.semantics().clauses().stream()
                .filter(candidate -> context.check().secondaryConcept() != null
                        && (candidate.effectivePrimaryConcept()
                                        .filter(concept -> concept
                                                == context.check().secondaryConcept())
                                        .isPresent()
                                || candidate.secondaryConcepts()
                                        .contains(context.check().secondaryConcept())))
                .toList();
        List<UUID> counterpartEvidence = List.of();
        if (!counterparts.isEmpty()) {
            var counterpartBundle = context.evidenceCache().build(request(context,
                    counterparts.get(0), context.check().secondaryConcept()));
            counterpartEvidence = subjectContractIds(counterpartBundle,
                    counterparts.stream().map(ClauseSemantics::id).toList());
            detail.put("counterpartTraceId", counterpartBundle.traceId());
        }
        var evidence = union(subjectContractIds(bundle, clause.id()),
                union(counterpartEvidence, policyIds(bundle)));
        var signal = new RawReviewSignal(
                UUID.randomUUID(), context.check().organizationId(),
                context.check().reviewId(), context.runId(), context.check().id(),
                SignalType.CROSS_CLAUSE_CONFLICT, FindingSubjectKind.CLAUSE,
                clause.headingNodeId(), null, context.check().concept(),
                context.check().secondaryConcept(), IssueFamily.SURVIVAL_COVERAGE_GAP,
                new CrossClauseConflictPayload("MISSING_SURVIVAL_COVERAGE",
                        "confidentiality obligations are not covered by survival provisions"),
                evidence, null, null, null, null, null, null, null,
                Map.of("sufficiency", bundle.sufficiency().name()),
                SignalType.CROSS_CLAUSE_CONFLICT + ":" + context.check().id(),
                context.executedAt());
        return CheckOutcome.completed(List.of(signal), detail, bundle.traceId());
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
            com.rivell.workbench.contracts.LegalConcept concept) {
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

    private static List<UUID> policyIds(EvidenceBundle bundle) {
        return bundle.policyItems().stream().map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new java.util.LinkedHashSet<UUID>();
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
