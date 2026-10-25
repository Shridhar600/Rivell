package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;

class SemanticConflictAnalyzerTest {

    @Test
    void longAndShortJurisdictionSpellingsAreTheSameScopeForExactDuplicates() {
        var organizationId = UUID.randomUUID();
        var candidate = candidate(organizationId, "United Kingdom");
        var peer = candidate(organizationId, "UK");

        var signals = new SemanticConflictAnalyzer(null, null)
                .inspect(candidate, List.of(peer));

        assertThat(signals).singleElement()
                .extracting(ConflictSignal::conflictType)
                .isEqualTo(ConflictType.DUPLICATE);
    }

    @Test
    void positionsStatedForDifferentCommercialTypesAreStillCompared() {
        var organizationId = UUID.randomUUID();
        var msaPosition = position(organizationId, "Payment terms",
                "Our position: net 30 days from a valid invoice.", Set.of("MSA"));
        var resellerPosition = position(organizationId, "Reseller payment terms",
                "Our position: net 45 days from a valid invoice.", Set.of("RESELLER"));

        var signals = analyzer().inspect(msaPosition, List.of(resellerPosition));

        assertThat(signals)
                .as("both govern a reseller agreement, so a contradiction there is material")
                .singleElement()
                .extracting(ConflictSignal::conflictType)
                .isEqualTo(ConflictType.CONTRADICTS_EXISTING);
    }

    @Test
    void positionsStatedForAnNdaAndAnMsaAreNotCompared() {
        var organizationId = UUID.randomUUID();
        var msaPosition = position(organizationId, "Payment terms",
                "Our position: net 30 days from a valid invoice.", Set.of("MSA"));
        var ndaPosition = position(organizationId, "Payment terms in confidence",
                "Our position: net 45 days from a valid invoice.", Set.of("NDA"));

        var signals = analyzer().inspect(msaPosition, List.of(ndaPosition));

        assertThat(signals)
                .as("confidentiality and commercial positions never govern the same contract")
                .isEmpty();
    }

    @Test
    void positionsDeclaredForDifferentMembersOfTheNdaFamilyAreCompared() {
        var organizationId = UUID.randomUUID();
        var ndaPosition = record(organizationId, "Payment terms",
                "Our position: net 30 days from a valid invoice.", CandidateType.PLAYBOOK_RULE,
                Set.of("NDA"), Set.of("NDA"));
        var mndaPosition = record(organizationId, "Mutual payment terms",
                "Our position: net 45 days from a valid invoice.", CandidateType.PLAYBOOK_RULE,
                Set.of("MNDA"), Set.of("NDA"));

        var signals = analyzer().inspect(ndaPosition, List.of(mndaPosition));

        assertThat(signals)
                .as("a mutual NDA contract reads both declared types")
                .singleElement()
                .extracting(ConflictSignal::conflictType)
                .isEqualTo(ConflictType.CONTRADICTS_EXISTING);
    }

    @Test
    void literalWordingIsNotComparedWhenNoContractTypeReadsBothRecords() {
        var organizationId = UUID.randomUUID();
        var msaWording = record(organizationId, "Payment terms",
                "Payment is due within 30 days.", CandidateType.CLAUSE_VARIANT,
                Set.of("MSA"), Set.of("MSA"));
        var sowWording = record(organizationId, "Payment terms for a statement of work",
                "Payment is due within 45 days.", CandidateType.CLAUSE_VARIANT,
                Set.of(), Set.of("SOW"));

        var signals = analyzer().inspect(msaWording, List.of(sowWording));

        assertThat(signals)
                .as("the MSA artifact excludes the SOW wording, and the SOW contract excludes "
                        + "the MSA artifact, so the two never meet")
                .isEmpty();
    }

    @Test
    void codesAreComparedTheWayRetrievalNormalizesThem() {
        var organizationId = UUID.randomUUID();
        var lowerCase = record(organizationId, "Payment terms",
                "Our position: net 30 days from a valid invoice.", CandidateType.PLAYBOOK_RULE,
                Set.of("msa"), Set.of("msa"));
        var canonical = record(organizationId, "Payment terms for master agreements",
                "Our position: net 45 days from a valid invoice.", CandidateType.PLAYBOOK_RULE,
                Set.of("MSA"), Set.of("MSA"));

        var signals = analyzer().inspect(lowerCase, List.of(canonical));

        assertThat(signals)
                .as("a manifest stored verbatim still names the same contract type")
                .singleElement()
                .extracting(ConflictSignal::conflictType)
                .isEqualTo(ConflictType.CONTRADICTS_EXISTING);
    }

    /**
     * A decision service that always judges the narrowed pair to contradict. The analyzer is
     * what decides whether the pair reaches the decision at all.
     */
    private static SemanticConflictAnalyzer analyzer() {
        var decisionService = new SemanticDecisionService(null, null, null) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                return new DecisionExecution(UUID.randomUUID(), spec.id(), spec.version(),
                        Map.of("contradicts", new YesNoAnswer(0.95)), null);
            }
        };
        return new SemanticConflictAnalyzer(decisionService,
                new SemanticPolicyProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.80, 0.80, 0, 0));
    }

    private static KnowledgeCandidate candidate(UUID organizationId, String jurisdiction) {
        var now = Instant.parse("2026-10-23T00:00:00Z");
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                UUID.randomUUID(), CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING,
                "Rule", "Rule content", CandidatePayload.EMPTY, "LIMITATION_OF_LIABILITY",
                List.of(), null, null, null, SemanticFields.EMPTY, null, List.of(),
                null, null, null,
                new ApplicabilityScope("MSA", Set.of("MSA"), "Customer", "Third-Party",
                        jurisdiction, LocalDate.of(2026, 1, 1), null),
                List.of(), ReviewCategory.ROUTINE, Set.of(), null, null, now, now, null);
    }

    private static KnowledgeCandidate position(
            UUID organizationId, String label, String wording, Set<String> sourceContractTypes) {
        return record(organizationId, label, wording, CandidateType.PLAYBOOK_RULE,
                Set.of(), sourceContractTypes);
    }

    private static KnowledgeCandidate record(
            UUID organizationId, String label, String wording, CandidateType candidateType,
            Set<String> declaredContractTypes, Set<String> sourceContractTypes) {
        var now = Instant.parse("2026-10-23T00:00:00Z");
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                UUID.randomUUID(), candidateType, ReviewStatus.PENDING,
                label, wording, CandidatePayload.EMPTY, "LIMITATION_OF_LIABILITY",
                List.of(), null, null, null, SemanticFields.EMPTY, null, List.of(),
                null, null, null,
                new ApplicabilityScope("MSA", declaredContractTypes, "Customer", "Third-Party",
                        "UK", LocalDate.of(2026, 1, 1), null, sourceContractTypes),
                List.of(), ReviewCategory.ROUTINE, Set.of(), null, null, now, now, null);
    }
}
