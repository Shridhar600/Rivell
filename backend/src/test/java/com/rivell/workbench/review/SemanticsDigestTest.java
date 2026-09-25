package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ConceptClassification;
import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.FactCorrection;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.Observations;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.QualityReasons;
import com.rivell.workbench.contracts.ReviewProfile;

class SemanticsDigestTest {

    private static final UUID SEMANTICS_ID = UUID
            .fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID
            .fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ORGANIZATION_ID = UUID
            .fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CLAUSE_ID = UUID
            .fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID HEADING_ID = UUID
            .fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void secondaryConceptChangeAltersTheDigest() {
        var base = semantics(List.of());
        var mutated = semantics(List.of(LegalConcept.LIMITATION_OF_LIABILITY));

        assertThat(SemanticsDigest.of(base)).isNotEqualTo(SemanticsDigest.of(mutated));
    }

    @Test
    void identicalSemanticsProduceTheSameDigest() {
        assertThat(SemanticsDigest.of(semantics(List.of(LegalConcept.INDEMNIFICATION))))
                .isEqualTo(SemanticsDigest.of(
                        semantics(List.of(LegalConcept.INDEMNIFICATION))));
    }

    private static ContractSemantics semantics(List<LegalConcept> secondary) {
        var clause = new ClauseSemantics(CLAUSE_ID, VERSION_ID, HEADING_ID,
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE, List.of(), "clause text",
                new ConceptClassification(List.of(LegalConcept.CONFIDENTIALITY), secondary,
                        Map.of()),
                List.of(), List.of(), new FactCorrection(null, Map.of(), null), null);
        return new ContractSemantics(SEMANTICS_ID, VERSION_ID, ORGANIZATION_ID,
                EnrichmentStatus.READY, ContractSemantics.PIPELINE_VERSION,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US, null),
                List.of(), List.of(), List.of(), List.of(), List.of(clause), List.of(),
                QualityReasons.of(List.of()),
                new Observations(1, 0, 0, 0, 0, false, false), List.of());
    }
}
