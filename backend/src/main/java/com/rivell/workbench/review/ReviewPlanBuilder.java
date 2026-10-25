package com.rivell.workbench.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Deterministic plan construction from snapshot-frozen inputs. Pack definitions own
 * WHICH concepts are checked for presence and cross-clause coherence; the builder owns
 * HOW the plan entries are derived. Every clause that carries at least one effective
 * topic gets a policy comparison, with or without a pack, because applicability comes
 * from the published playbook's topic sets, not from code. Subjects are stable
 * canonical heading-node identities.
 */
public final class ReviewPlanBuilder {

    private final ReviewPackRegistry packs;
    private final boolean omissions;

    public ReviewPlanBuilder(ReviewPackRegistry packs, boolean omissions) {
        this.packs = packs;
        this.omissions = omissions;
    }

    public List<ReviewPlanCheck> build(
            UUID reviewId, UUID organizationId, ReviewSnapshot snapshot,
            ContractSemantics semantics) {
        var checks = new ArrayList<ReviewPlanCheck>();
        var ordinal = new int[] {0};
        ReviewPack pack = null;

        if (snapshot.contractPackKey() != null) {
            pack = packs.find(snapshot.contractPackKey())
                    .orElseThrow(() -> new InvalidReviewConfigurationException(
                            "That review pack is not available."));
            for (var entry : pack.conceptExpectations().entrySet()) {
                checks.add(presenceCheck(reviewId, organizationId, snapshot.createdAt(),
                        ordinal[0]++, entry.getKey(), entry.getValue()));
            }
        }
        for (var clause : semantics.clauses()) {
            // every clause gets a check: one with no topic is still triaged as uncovered
            var topics = ClauseTopics.effectiveTopics(clause);
            checks.add(policyComparisonCheck(reviewId, organizationId, snapshot.createdAt(),
                    ordinal[0]++, ClauseTopics.subjectConcept(topics), clause.headingNodeId()));
        }
        if (pack != null) {
            var clausesByConcept = clausesByConcept(semantics);
            for (var rule : pack.crossClauseChecks()) {
                if (clausesByConcept.getOrDefault(rule.secondaryConcept(), List.of()).isEmpty()) {
                    continue;
                }
                for (var subject : clausesByConcept.getOrDefault(rule.concept(), List.of())) {
                    checks.add(crossClauseCheck(reviewId, organizationId,
                            snapshot.createdAt(), ordinal[0]++, rule,
                            subject.headingNodeId()));
                }
            }
        }
        if (omissions) {
            // engine v2 omissions lane (spec §4): one check, last, once every clause was judged
            checks.add(omissionCheck(reviewId, organizationId, snapshot.createdAt(), ordinal[0]++));
        }
        return List.copyOf(checks);
    }

    private static java.util.Map<ConceptKey, List<ClauseSemantics>> clausesByConcept(
            ContractSemantics semantics) {
        var byConcept = new java.util.LinkedHashMap<ConceptKey, List<ClauseSemantics>>();
        for (var clause : semantics.clauses()) {
            clause.effectivePrimaryConcept().ifPresent(concept ->
                    byConcept.computeIfAbsent(concept, key -> new ArrayList<>()).add(clause));
        }
        return byConcept;
    }

    private static ReviewPlanCheck presenceCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ConceptKey concept, CheckExpectation expectation) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "PRESENCE:" + concept, CheckFamily.CONCEPT_PRESENCE, expectation,
                CheckMechanism.PRESENCE, concept, null, null, createdAt);
    }

    private static ReviewPlanCheck policyComparisonCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ConceptKey concept, UUID subjectHeadingNodeId) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "CLAUSE:" + CheckMechanism.POLICY_COMPARISON + ":" + concept + ":"
                        + subjectHeadingNodeId,
                CheckFamily.CLAUSE_REVIEW, null, CheckMechanism.POLICY_COMPARISON, concept,
                null, subjectHeadingNodeId, createdAt);
    }

    private static ReviewPlanCheck crossClauseCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ReviewPack.CrossClauseRule rule, UUID subjectHeadingNodeId) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "CROSS:" + rule.mechanism() + ":" + rule.concept() + ":"
                        + rule.secondaryConcept() + ":" + subjectHeadingNodeId,
                CheckFamily.CROSS_CLAUSE, null, rule.mechanism(), rule.concept(),
                rule.secondaryConcept(), subjectHeadingNodeId, createdAt);
    }

    /** Engine v2 omissions lane: no subject; the check reads the run's clause comparisons. */
    private static ReviewPlanCheck omissionCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "OMISSION", CheckFamily.CONCEPT_PRESENCE, CheckExpectation.REQUIRED,
                CheckMechanism.OMISSION, ClauseTopics.NO_TOPIC, null, null, createdAt);
    }
}
