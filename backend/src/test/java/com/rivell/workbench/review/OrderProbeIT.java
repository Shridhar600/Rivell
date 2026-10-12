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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Engine v2 P0: the order probe asks every policy comparison a second time with the options
 * reversed. Its answers are kept for measurement and never change a finding, also when the
 * probe disagrees or fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class OrderProbeIT {

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
        registry.add("rivell.review.policy-comparison.order-probe", () -> "reversed");
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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

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

    /** pc-contract.docx against the PC playbook: the flow PolicyComparisonFlowIT pins. */
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

    private static List<IssueFamily> families(List<Finding> findings) {
        return findings.stream().map(Finding::issueFamily).toList();
    }

    @Test
    void aReversedProbeRecordsBothAnswersAndNeverChangesTheFindings() throws Exception {
        var reviewId = reviewedContract();
        // the probe sees every position as compliant: a real disagreement on the flagged ones
        scriptedProvider.reversedOrderAnswer = PolicyComparisonSpecs.MEETS_PREFERRED;

        var run = analysisService.analyze(organizationId(), reviewId);

        // the same findings as without the probe (PolicyComparisonFlowIT): walk-away + within fallback
        assertThat(families(findingRepository.findFindings(organizationId(), run.id())))
                .containsExactlyInAnyOrder(IssueFamily.POSITION_WALK_AWAY,
                        IssueFamily.POSITION_ACCEPTABLE_FALLBACK);
        // every comparison asked twice: once as written, once reversed
        var calls = scriptedProvider.policyComparisonCalls();
        assertThat(calls).hasSize(8);
        assertThat(calls)
                .filteredOn(call -> PolicyComparisonSpecs.NOT_ADDRESSED.equals(call.firstOption()))
                .hasSize(4);
        // the detail keeps both answers side by side
        var flaggedCheck = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> String.valueOf(execution.detail().get("positions"))
                        .contains("flagged=true"))
                .findFirst().orElseThrow();
        assertThat(flaggedCheck.detail()).containsEntry("probe", "REVERSED");
        var flagged = ((List<?>) flaggedCheck.detail().get("positions")).stream()
                .map(entry -> (Map<?, ?>) entry)
                .filter(entry -> Boolean.TRUE.equals(entry.get("flagged")))
                .findFirst().orElseThrow();
        assertThat(flagged.get("probeFlagged")).isEqualTo(false);
        assertThat(flagged.get("probeCategory")).isEqualTo("");
        assertThat(((Map<?, ?>) flagged.get("probeProbabilities"))
                .containsKey(PolicyComparisonSpecs.MEETS_PREFERRED)).isTrue();
    }

    @Test
    void aFailedProbeLeavesTheCheckAndItsFindingsAlone() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.failReversedOrder = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(families(findingRepository.findFindings(organizationId(), run.id())))
                .containsExactlyInAnyOrder(IssueFamily.POSITION_WALK_AWAY,
                        IssueFamily.POSITION_ACCEPTABLE_FALLBACK);
        var compared = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .toList();
        assertThat(compared).isNotEmpty().allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
            assertThat(execution.detail()).containsEntry("probe", "UNAVAILABLE");
        });
    }
}
