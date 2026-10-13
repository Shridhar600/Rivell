package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
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
 * Engine v2 advisory picks with {@code advisory-spots=HIGH}: only the whole-contract read's own
 * HIGH picks become findings; a MEDIUM pick stays in the check detail.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class AdvisoryPickHighOnlyIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String GOVERNING_LAW = "Governing Law";

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
        registry.add("rivell.review.engine", () -> "v2");
        registry.add("rivell.review.policy-comparison.advisory-spots", () -> "HIGH");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

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
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook (the flow PositionSearchRouteIT pins). */
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

    private List<Finding> findings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId);
    }

    private static List<Finding> spottedFindings(List<Finding> findings) {
        return findings.stream()
                .filter(finding -> PolicyUnavailablePayload.SPOTTED.equals(finding.observationKind()))
                .toList();
    }

    /** The check execution whose detail records the read's pick at this severity. */
    private ReviewCheckExecution spottedCheck(UUID runId, String severity) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> severity.equals(execution.detail().get("spotted")))
                .findFirst().orElseThrow();
    }

    @Test
    void aMediumPickStaysInTheDetailAndMakesNoFinding() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        // the pick is still recorded for the diagnostics
        assertThat(spottedCheck(run.id(), "MEDIUM").detail()).containsEntry("addressed", false);
    }

    @Test
    void aHighPickStillBecomesAnAdvisoryItem() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHighHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(1);
        assertThat(spotted.getFirst().severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(spotted.getFirst().quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("spottedSeverity", "HIGH");
    }
}
