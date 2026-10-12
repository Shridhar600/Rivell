package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.fasterxml.jackson.databind.JsonNode;
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
 * P2b, human control over misses (design 2026-10-12 §9): the user sees what Rivell did with every
 * clause, and can add a finding Rivell missed; the added finding is decided, finalized and
 * redlined like any other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class UserControlIT {

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
    ReviewRepository reviewRepository;

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

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, String> marks(UUID reviewId) throws Exception {
        var marks = new LinkedHashMap<String, String>();
        json("/api/reviews/{id}/clause-marks", reviewId).path("data").path("clauses")
                .forEach(clause -> marks.put(clause.path("headingNodeId").asText(),
                        clause.path("mark").asText()));
        return marks;
    }

    @Test
    void everyPlannedClauseIsMarkedAndTheFlaggedOnesMatchTheFindings() throws Exception {
        var reviewId = reviewedContract();
        assertThat(json("/api/reviews/{id}/clause-marks", reviewId).path("data").path("clauses"))
                .as("no completed analysis, no marks").isEmpty();
        var run = analysisService.analyze(organizationId(), reviewId);

        var marks = marks(reviewId);

        var planned = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .map(ReviewPlanCheck::subjectHeadingNodeId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toSet());
        assertThat(marks.keySet()).isEqualTo(planned);
        var flagged = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.CLAUSE)
                .map(finding -> finding.subjectHeadingNodeId().toString())
                .collect(Collectors.toSet());
        assertThat(flagged).isNotEmpty();
        assertThat(marks).allSatisfy((heading, mark) ->
                assertThat(mark.equals("FLAGGED")).as(heading).isEqualTo(flagged.contains(heading)));
        assertThat(List.copyOf(marks.values())).contains("CHECKED");
    }
}
