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
import com.rivell.workbench.review.DeviationUncertainPayload;
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

        // Coverage must not become falsely clean when several clauses carry the
        // concept: one SURVIVES relationship cannot prove every obligation is
        // covered. Multi-clause coverage is clean only when every candidate clause
        // is an endpoint of a survival relationship; partial/ambiguous coverage is
        // explicit uncertainty, never a fabricated per-clause verdict (DW-027).
        var candidates = context.semantics().clauses().stream()
                .filter(candidate -> candidate.effectivePrimaryConcept()
                        .filter(concept -> concept == context.check().concept())
                        .isPresent())
                .toList();
        var survivalEndpoints = context.semantics().relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.SURVIVES)
                .flatMap(relationship -> java.util.stream.Stream.of(
                        relationship.sourceRegionNodeId(), relationship.targetRegionNodeId()))
                .collect(java.util.stream.Collectors.toSet());
        var relationshipCoveredCount = candidates.stream()
                .filter(candidate -> survivalEndpoints.contains(candidate.headingNodeId()))
                .count();
        var coveredByEvidence = bundle.currentContractItems().stream()
                .anyMatch(item -> item.retrievalReason() != null
                        && item.retrievalReason().contains("SURVIVES"));
        var subjectCoveredByRelationship = survivalEndpoints.contains(clause.headingNodeId());

        boolean covered;
        boolean uncertain = false;
        String basis;
        if (subjectCoveredByRelationship || coveredByEvidence) {
            covered = true;
            basis = subjectCoveredByRelationship ? "relationship" : "evidence";
        } else if (relationshipCoveredCount > 0) {
            // Survival semantics exist for the concept but cannot be established for
            // this clause; the limitation is explicit instead of a fabricated verdict.
            covered = false;
            uncertain = true;
            basis = "partial";
        } else {
            covered = false;
            basis = "none";
        }

        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("covered", covered);
        detail.put("basis", basis);
        detail.put("candidateClauseCount", candidates.size());
        detail.put("relationshipCoveredCount", relationshipCoveredCount);
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
        com.rivell.workbench.retrieval.EvidenceBundle counterpartBundle = null;
        if (!counterparts.isEmpty()) {
            counterpartBundle = context.evidenceCache().build(request(context,
                    counterparts.get(0), context.check().secondaryConcept()));
            counterpartEvidence = subjectContractIds(counterpartBundle,
                    counterparts.stream().map(ClauseSemantics::id).toList());
            detail.put("counterpartTraceId", counterpartBundle.traceId());
        }
        var evidence = union(subjectContractIds(bundle, clause.id()),
                union(counterpartEvidence, policyIds(bundle)));
        if (uncertain) {
            // Coverage exists for the concept but cannot be established for this
            // clause; the limitation is explicit (DEGRADED warning) instead of a
            // fabricated per-clause verdict.
            detail.put("uncertaintyReason", "survival_coverage_partial");
            return CheckOutcome.degraded("survival_coverage_partial", List.of(), detail,
                            bundle.traceId())
                    .withCitedEvidence(counterpartBundle == null
                            ? List.of(bundle) : List.of(bundle, counterpartBundle));
        }
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
        return CheckOutcome.completed(List.of(signal), detail, bundle.traceId())
                .withCitedEvidence(counterpartBundle == null
                        ? List.of(bundle) : List.of(bundle, counterpartBundle));
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
