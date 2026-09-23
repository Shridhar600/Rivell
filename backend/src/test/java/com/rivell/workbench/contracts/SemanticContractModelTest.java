package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SemanticContractModelTest {

    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID NODE_ID = UUID.randomUUID();

    @Test
    void interpretationRequiresProvenance() {
        var interpretation = new Interpretation(
                "primaryConcept", InterpretationKind.BOUNDED_CLASSIFICATION,
                "LIMITATION_OF_LIABILITY", "contract-clause-legal-concept", 1,
                UUID.randomUUID(), List.of(NODE_ID), VerificationState.NOT_VERIFIED, null);

        assertThat(interpretation.field()).isEqualTo("primaryConcept");
        assertThatThrownBy(() -> new Interpretation(
                " ", InterpretationKind.DETERMINISTIC, "x", "spec", 1, null,
                List.of(), VerificationState.VERIFIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiedGenerativeInterpretationRequiresVerificationExecution() {
        assertThatThrownBy(() -> new Interpretation(
                "liabilityCapMultiple", InterpretationKind.GENERATIVE_EXTRACTION,
                "3", "liability-cap-extraction", 1, UUID.randomUUID(),
                List.of(NODE_ID), VerificationState.VERIFIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contractPartyRecordsRawNameAliasesAndRoleState() {
        var party = new ContractParty(UUID.randomUUID(), VERSION_ID,
                "Helios Systems Inc.", "helios systems inc.", List.of("Helios", "the Discloser"),
                List.of(new Interpretation("roles", InterpretationKind.BOUNDED_CLASSIFICATION,
                        "DISCLOSING_PARTY", "contract-party-role", 1, UUID.randomUUID(),
                        List.of(NODE_ID), VerificationState.NOT_VERIFIED, null)),
                List.of(PartyRole.DISCLOSING_PARTY),
                PartyCorrection.NONE, null);

        assertThat(party.effectiveRoles()).containsExactly(PartyRole.DISCLOSING_PARTY);
        assertThat(party.roleCorrected()).isFalse();
    }

    @Test
    void humanPartyRoleCorrectionOverridesWithoutErasingMachineProposal() {
        var machine = new Interpretation("roles", InterpretationKind.BOUNDED_CLASSIFICATION,
                "RECEIVING_PARTY", "contract-party-role", 1, UUID.randomUUID(),
                List.of(NODE_ID), VerificationState.NOT_VERIFIED, null);
        var party = new ContractParty(UUID.randomUUID(), VERSION_ID,
                "Vertex Analytics Ltd.", "vertex analytics ltd.", List.of(),
                List.of(machine), List.of(PartyRole.RECEIVING_PARTY),
                PartyCorrection.of(List.of(PartyRole.DISCLOSING_PARTY)), UUID.randomUUID());

        assertThat(party.effectiveRoles()).containsExactly(PartyRole.DISCLOSING_PARTY);
        assertThat(party.roleCorrected()).isTrue();
        assertThat(party.machineRoles()).containsExactly(PartyRole.RECEIVING_PARTY);
    }

    @Test
    void clauseSemanticsKeepPrimarySecondaryAndFactsSeparate() {
        var liabilityFacts = new LegalFact("liability-facts", 1, Map.of(
                "liabilityCapMultiple", "3",
                "capBasis", "PRECEDING_12_MONTH_FEES",
                "consequentialDamagesExcluded", "true"));
        var clause = new ClauseSemantics(UUID.randomUUID(), VERSION_ID, NODE_ID,
                List.of(UUID.randomUUID()), "Supplier liability shall not exceed three times fees...",
                new ConceptClassification(List.of(LegalConcept.LIMITATION_OF_LIABILITY),
                        List.of(LegalConcept.INDEMNIFICATION),
                        Map.of(LegalConcept.LIMITATION_OF_LIABILITY, 0.93)),
                List.of(liabilityFacts), List.of(), FactCorrection.NONE, null);

        assertThat(clause.primaryConcept()).contains(LegalConcept.LIMITATION_OF_LIABILITY);
        assertThat(clause.secondaryConcepts()).containsExactly(LegalConcept.INDEMNIFICATION);
        assertThat(clause.facts()).hasSize(1);
    }

    @Test
    void factSchemaRejectsUnknownFields() {
        assertThatThrownBy(() -> new LegalFact("liability-facts", 1,
                Map.of("noticePeriodDays", "30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalFact("unknown-schema", 1, Map.of("x", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalFact("termination-facts", 1,
                Map.of("noticePeriodDays", "-5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referenceRecordsResolutionState() {
        var resolved = new ContractReference(UUID.randomUUID(), VERSION_ID,
                NODE_ID, "Section 6", ReferenceKind.SECTION, ReferenceStatus.RESOLVED,
                UUID.randomUUID(), List.of());
        var broken = new ContractReference(UUID.randomUUID(), VERSION_ID,
                NODE_ID, "Section 99", ReferenceKind.SECTION, ReferenceStatus.BROKEN,
                null, List.of());

        assertThat(resolved.resolved()).isTrue();
        assertThat(broken.resolved()).isFalse();
    }

    @Test
    void relationshipCarriesDetectorAndBasis() {
        var relationship = new SemanticRelationship(UUID.randomUUID(), VERSION_ID,
                UUID.randomUUID(), UUID.randomUUID(), RelationshipType.CARVES_OUT,
                RelationshipDetector.SEMANTIC, "explicit carve-out language", 0.86,
                UUID.randomUUID(), VerificationState.NOT_VERIFIED, null);

        assertThat(relationship.type()).isEqualTo(RelationshipType.CARVES_OUT);
        assertThat(relationship.probability()).isBetween(0.0, 1.0);
    }

    @Test
    void semanticsCarryLifecycleAndProfile() {
        var reviewProfile = new ReviewProfile(ContractType.NDA, PartyRole.DISCLOSING_PARTY,
                PaperSource.OUR_PAPER, Jurisdiction.US);
        var semantics = new ContractSemantics(UUID.randomUUID(), VERSION_ID, ORG_ID,
                EnrichmentStatus.READY, "rivell-contract-enrichment-1", reviewProfile,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                QualityReasons.of(List.of(ReviewQualityNote.NONE)), null);

        assertThat(semantics.enrichmentStatus()).isEqualTo(EnrichmentStatus.READY);
        assertThat(semantics.reviewProfile().contractType()).isEqualTo(ContractType.NDA);
    }
}
