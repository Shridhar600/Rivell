package com.rivell.workbench.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class WorkflowSchemaIT {

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
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void duplicateDecisionRevisionIsRejectedByTheDatabase() throws Exception {
        var setup = acceptedMainCorpusReview();

        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into human_decision
                  (id, organization_id, review_id, finding_id, run_id, revision, action,
                   approved_action, comment, actor_id, decided_at)
                values (:id, :organizationId, :reviewId, :findingId, :runId, 1, 'REJECT',
                   null, 'duplicate revision', :actorId, :decidedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", setup.organizationId())
                .param("reviewId", setup.reviewId())
                .param("findingId", setup.findingId())
                .param("runId", setup.runId())
                .param("actorId", UUID.randomUUID())
                .param("decidedAt", now())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidDecisionCombinationIsRejectedByTheDatabase() throws Exception {
        var setup = acceptedMainCorpusReview();

        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into human_decision
                  (id, organization_id, review_id, finding_id, run_id, revision, action,
                   approved_action, actor_id, decided_at)
                values (:id, :organizationId, :reviewId, :findingId, :runId, 2, 'ACCEPT',
                   null, :actorId, :decidedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", setup.organizationId())
                .param("reviewId", setup.reviewId())
                .param("findingId", setup.findingId())
                .param("runId", setup.runId())
                .param("actorId", UUID.randomUUID())
                .param("decidedAt", now())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossOrganizationDecisionIsRejectedByTheDatabase() throws Exception {
        var setup = acceptedMainCorpusReview();

        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into human_decision
                  (id, organization_id, review_id, finding_id, run_id, revision, action,
                   approved_action, comment, actor_id, decided_at)
                values (:id, :organizationId, :reviewId, :findingId, :runId, 2, 'REJECT',
                   null, 'foreign org', :actorId, :decidedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", UUID.randomUUID())
                .param("reviewId", setup.reviewId())
                .param("findingId", setup.findingId())
                .param("runId", setup.runId())
                .param("actorId", UUID.randomUUID())
                .param("decidedAt", now())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondFinalizationAndDuplicateClauseTargetAreRejectedByTheDatabase() throws Exception {
        var fixture = analyzedCarveOutConflict();
        var sameClause = fixture.findings().stream()
                .filter(finding -> finding.subjectHeadingNodeId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        Finding::subjectHeadingNodeId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values().stream()
                .filter(group -> group.size() >= 2)
                .findFirst()
                .orElseThrow();
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification() == FindingClassification.INFORMATIONAL
                    || sameClause.stream().anyMatch(member -> member.id().equals(finding.id()))) {
                continue;
            }
            decide(fixture.review().id(), finding.id(), """
                    {"expectedVersion": 0, "action": "REJECT", "comment": "Not material."}
                    """);
        }
        decide(fixture.review().id(), sameClause.get(0).id(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "REPLACE",
                 "approvedLanguage": "Single frozen replacement."}
                """);
        var rejected = decide(fixture.review().id(), sameClause.get(1).id(), """
                {"expectedVersion": 0, "action": "REJECT", "comment": "Consolidated."}
                """);
        finalize(fixture.review().id());

        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into review_finalization
                  (id, organization_id, review_id, run_id, contract_id, contract_version_id,
                   contract_version_number, change_set_version, finalized_by, finalized_at)
                values (:id, :organizationId, :reviewId, :runId, :contractId, :versionId,
                   1, 1, :finalizedBy, :finalizedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", fixture.review().organizationId())
                .param("reviewId", fixture.review().id())
                .param("runId", fixture.run().id())
                .param("contractId", fixture.review().contractId())
                .param("versionId", fixture.review().contractVersionId())
                .param("finalizedBy", UUID.randomUUID())
                .param("finalizedAt", now())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        var changeSetId = jdbcClient.sql("""
                select id from review_finalization where review_id = :reviewId
                """).param("reviewId", fixture.review().id()).query(UUID.class).single();
        assertThatThrownBy(() -> jdbcClient.sql("""
                insert into approved_change
                  (id, organization_id, change_set_id, review_id, ordinal, finding_id,
                   decision_id, subject_kind, action_type, target_heading_node_id,
                   approved_language, language_source)
                values (:id, :organizationId, :changeSetId, :reviewId, 9, :findingId,
                   :decisionId, 'CLAUSE', 'REPLACE', :headingNodeId,
                   'Duplicate target text.', 'HUMAN_EDIT')
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", fixture.review().organizationId())
                .param("changeSetId", changeSetId)
                .param("reviewId", fixture.review().id())
                .param("findingId", sameClause.get(1).id())
                .param("decisionId", UUID.fromString(rejected.get("data").get("id").asText()))
                .param("headingNodeId", sameClause.get(1).subjectHeadingNodeId())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Setup acceptedMainCorpusReview() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(
                identityProvider.currentIdentity().organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(
                identityProvider.currentIdentity().organizationId(), review.id());
        var finding = findingRepository
                .findFindings(identityProvider.currentIdentity().organizationId(), run.id())
                .stream()
                .filter(candidate -> candidate.proposedAction()
                        == ProposedAction.USE_APPROVED_FALLBACK)
                .findFirst().orElseThrow();
        var decision = decide(review.id(), finding.id(), """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);
        return new Setup(review.organizationId(), review.id(), run.id(), finding.id(),
                UUID.fromString(decision.get("data").get("id").asText()));
    }

    private record Fixture(Review review, ReviewAnalysisRun run, List<Finding> findings) {
    }

    private Fixture analyzedCarveOutConflict() throws Exception {
        corpusBuilder.publishAll(identityProvider.currentIdentity().organizationId(),
                M6EvidenceCorpus.Fixture.POLICY_MAIN, M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(identityProvider.currentIdentity().organizationId(),
                M6EvidenceCorpus.Fixture.LIBRARY_MAIN, M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m7-msa-carveout-conflict.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-carveout-conflict.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(
                identityProvider.currentIdentity().organizationId(), review.id());
        return new Fixture(review, run, findingRepository.findFindings(
                identityProvider.currentIdentity().organizationId(), run.id()));
    }

    private JsonNode decide(UUID reviewId, UUID findingId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(
                        "/api/reviews/{id}/findings/{findingId}/decision", reviewId, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void finalize(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/finalize", reviewId))
                .andExpect(status().isOk());
    }

    private record Setup(
            UUID organizationId, UUID reviewId, UUID runId, UUID findingId, UUID decisionId) {
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
