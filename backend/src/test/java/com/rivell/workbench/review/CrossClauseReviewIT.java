package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
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

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class CrossClauseReviewIT {

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
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review enrichedReviewWithPack(String fixture) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        return reviewService.create(ingested.contractId(), 1, null);
    }

    @Test
    void survivalCoverageIsDeterministicallyProvenForTheMsaCorpus() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var coverageExecutions = analysisRepository.findExecutions(organizationId, run.id())
                .stream().filter(execution -> execution.detail().containsKey("covered"))
                .toList();
        assertThat(coverageExecutions).isNotEmpty();
        assertThat(coverageExecutions).allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
            assertThat(execution.detail().get("covered")).isEqualTo(true);
        });
        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
    }

    @Test
    void missingSurvivalCoverageProducesDeterministicConflictSignal() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReviewWithPack("m7-msa-survival-gap.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var conflicts = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP)
                .toList();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).signalType()).isEqualTo(SignalType.CROSS_CLAUSE_CONFLICT);
        assertThat(conflicts.get(0).evidenceItemIds()).isNotEmpty();
        assertThat(conflicts.get(0).jevExecutionId()).isNull();
        var survivalExecution = analysisRepository.findExecutions(organizationId, run.id())
                .stream().filter(execution -> execution.detail().containsKey("covered"))
                .findFirst().orElseThrow();
        assertThat(survivalExecution.detail().get("covered")).isEqualTo(false);
        assertThat(survivalExecution.detail().get("basis")).isEqualTo("none");
    }

    @Test
    void carveOutConflictProducesBoundedConflictWithProvenance() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReviewWithPack("m7-msa-carveout-conflict.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var conflicts = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY
                        && signal.signalType() == SignalType.CROSS_CLAUSE_CONFLICT)
                .toList();
        assertThat(conflicts).hasSize(1);
        var payload = (CrossClauseConflictPayload) conflicts.get(0).payload();
        assertThat(payload.conflictType())
                .isEqualTo(M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY);
        assertThat(conflicts.get(0).jevExecutionId()).isNotNull();
        assertThat(conflicts.get(0).resolvedModel()).isNotBlank();
        assertThat(conflicts.get(0).evidenceItemIds()).isNotEmpty();
        var carveExecution = analysisRepository.findExecutions(organizationId, run.id())
                .stream().filter(execution -> execution.detail().containsKey("consistency"))
                .findFirst().orElseThrow();
        assertThat(carveExecution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(carveExecution.detail().get("consistency"))
                .isEqualTo(M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY);
    }

    @Test
    void uncappedIndemnityWithCarveOutIsNotAContradiction() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReviewWithPack("m7-msa-carveout-consistent.docx");

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY);
        var carveExecution = analysisRepository.findExecutions(organizationId, run.id())
                .stream().filter(execution -> execution.detail().containsKey("consistency"))
                .findFirst().orElseThrow();
        assertThat(carveExecution.detail().get("consistency"))
                .isEqualTo(M7DecisionSpecs.CONSISTENT);
    }

    @Test
    void consistentCarveOutProducesNoConflict() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY);
        var carveExecution = analysisRepository.findExecutions(organizationId, run.id())
                .stream().filter(execution -> execution.detail().containsKey("consistency"))
                .findFirst().orElseThrow();
        assertThat(carveExecution.detail().get("consistency"))
                .isEqualTo(M7DecisionSpecs.CONSISTENT);
    }
}
