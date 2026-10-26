package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * DW-106 R1 calibration: with the corroboration rule on, a disagreement the second opinion calls a
 * walk-away or beyond-fallback raises to MEDIUM only when a second signal on the clause agrees —
 * the fallback-condition check found the clause PARTIAL, or the whole-contract read named it.
 * Without either signal the item stays LOW. The rule is off by default; SecondOpinionIT pins that
 * behaviour.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class SeverityR1CorroborationIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.review.severity.r1-needs-corroboration", () -> "true");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    /** Jev leans to "meets" without conviction: flag mass 0.51, inside the band, not flagged. */
    private static final Map<String, Double> UNSURE_PASS = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.45,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.02,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.45,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.04);
    private static final String LIABILITY = "Limitation of Liability";

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private UUID reviewedContract() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private void jevUnsureOnLiability(Map<String, Double> probabilities) {
        scriptedProvider.policyComparisonProbabilities = probabilities;
        scriptedProvider.probabilityHeadings.add(LIABILITY);
    }

    private Finding only(UUID runId, IssueFamily family) {
        var matching = findingRepository.findFindings(organizationId(), runId).stream()
                .filter(finding -> finding.issueFamily() == family).toList();
        assertThat(matching).as("findings of family %s", family).hasSize(1);
        return matching.getFirst();
    }

    @Test
    void anUncorroboratedDisagreementStaysLowAndRecordsThatNoSignalAgreed() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_PASS);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        var look = only(run.id(), IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(look.quality())
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("levers", List.of(PolicyComparisonSpecs.R1_NONE));
        assertThat(generativeProvider.fallbackConditionCalls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aPartialFallbackConditionRaisesTheDisagreement() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_PASS);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;
        // one line per liability position: the clause does not state a condition of its fallback
        generativeProvider.fallbackConditionLines =
                "1 | UNKNOWN | - | the cap is two times the fees paid in the preceding twelve months"
                        + " | The total liability of Supplier shall not exceed two times the fees paid"
                        + " in the preceding 12 months.\n"
                        + "1 | UNKNOWN | - | the cap applies to third-party claims"
                        + " | The liability cap may apply to third-party claims.";

        var run = analysisService.analyze(organizationId(), reviewId);

        var look = only(run.id(), IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(look.quality())
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("levers", List.of(PolicyComparisonSpecs.R1_P3));
    }

    @Test
    void aWholeContractPickRaisesTheDisagreement() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_PASS);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;
        generativeProvider.spottedHeadings.add(LIABILITY);

        var run = analysisService.analyze(organizationId(), reviewId);

        var look = only(run.id(), IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(look.quality())
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("levers", List.of(PolicyComparisonSpecs.R1_SPOT));
        assertThat(generativeProvider.spottingCalls).hasValue(1);
    }
}
