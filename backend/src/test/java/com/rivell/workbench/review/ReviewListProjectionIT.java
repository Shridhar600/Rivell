package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.ReviewFinalizationService;

/**
 * M10B-B2: the reviews list must carry enough real backend truth for the canonical
 * Reviews screen to be built without inventing anything.
 *
 * <p>Every value asserted here is produced by the real services. Findings come from a
 * real analysis run, decisions from the real decision service, finalization from the
 * real finalization service and the redline from the real renderer and verifier. No
 * row is inserted to make an assertion pass, so a passing test means the list reports
 * what the domain actually decided rather than what a projection assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewListProjectionIT {

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
    HumanDecisionService decisionService;

    @Autowired
    ReviewFinalizationService finalizationService;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    /**
     * R1: the frozen, human-declared review context travels with the row. It comes from
     * the snapshot the review was created against, so a later re-enrichment cannot
     * rewrite what a row claims the reviewer declared.
     */
    @Test
    void rowsCarryTheFrozenHumanDeclaredReviewContext() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1, new ReviewProfile(
                ContractType.NDA, PartyRole.CUSTOMER, PaperSource.THIRD_PARTY,
                Jurisdiction.UK));
        var review = reviewService.create(ingested.contractId(), 1, null, null);
        analysisService.analyze(organizationId(), review.id());

        var row = rowFor(review.id());

        assertThat(row.get("context").get("contractType").asText()).isEqualTo("NDA");
        assertThat(row.get("context").get("ourSideRole").asText()).isEqualTo("CUSTOMER");
        assertThat(row.get("context").get("paperSource").asText()).isEqualTo("THIRD_PARTY");
        assertThat(row.get("context").get("jurisdiction").asText()).isEqualTo("UK");
    }

    /**
     * R1 again, on a minimally declared review. Two of the five context columns are
     * genuinely nullable and jurisdiction's "unset" value is a real enum constant, so a
     * row must report that honestly rather than invent a side or a paper source.
     */
    @Test
    void aMinimalProfileReportsNullsRatherThanInventedContext() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.OTHER));
        var review = reviewService.create(ingested.contractId(), 1, null, null);

        var row = rowFor(review.id());

        assertThat(row.get("context").get("contractType").asText()).isEqualTo("OTHER");
        assertThat(row.get("context").get("ourSideRole").isNull()).isTrue();
        assertThat(row.get("context").get("paperSource").isNull()).isTrue();
        assertThat(row.get("context").get("jurisdiction").asText())
                .isEqualTo("UNSPECIFIED");
    }

    /**
     * R5: "Uploaded" is when the version this review is pinned to was ingested. It must
     * come from that version, never from the contract's latest version and never from
     * the review's own creation time, which would silently relabel review time as
     * upload time.
     */
    @Test
    void uploadedComesFromThePinnedVersionAndNotTheReviewCreationTime() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.NDA));
        var review = reviewService.create(ingested.contractId(), 1, null, null);

        var row = rowFor(review.id());

        var versionUploadedAt = jdbcInstant(
                "select created_at from contract_version where id = :id",
                review.contractVersionId());
        assertThat(row.get("uploadedAt").asText())
                .as("uploaded must be the pinned version's own ingestion time")
                .isEqualTo(versionUploadedAt);
        assertThat(row.get("uploadedAt").asText())
                .as("and must not be the review's creation time relabelled as upload time")
                .isNotEqualTo(row.get("createdAt").asText());
    }

    /**
     * R2: readiness is server-owned and must equal what the human-review endpoint
     * already reports for the same review. If the list ever disagreed with
     * {@code GET /api/reviews/{id}/human-review} the frontend would be told two
     * different truths about the same review.
     */
    @Test
    void humanReviewCountsAndReadinessMatchTheHumanReviewEndpoint() throws Exception {
        var fixture = analyzedCarveOutConflict();
        var actionable = distinctActionable(fixture);
        assertThat(actionable.size())
                .as("this test needs at least two independently decidable findings")
                .isGreaterThanOrEqualTo(2);

        var expectedActionable = actionable.size();
        // ACCEPT needs an executable recommendation; a finding proposed as
        // ESCALATE_TO_HUMAN legitimately rejects it, so pick one explicitly rather than
        // assuming list order.
        var accepted = findingWith(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var escalated = actionable.stream()
                .filter(f -> !f.id().equals(accepted.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "need a second actionable finding to escalate"));

        decide(fixture.review().id(), accepted.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        decide(fixture.review().id(), escalated.id(),
                "{\"expectedVersion\": 0, \"action\": \"ESCALATE\","
                        + " \"comment\": \"Needs GC review.\"}");

        var row = rowFor(fixture.review().id());
        var human = row.get("humanReview");

        assertThat(human.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(human.get("actionableFindings").asInt()).isEqualTo(expectedActionable);
        assertThat(human.get("resolvedFindings").asInt())
                .as("an escalated decision is the only unresolved action")
                .isEqualTo(1);
        assertThat(human.get("remainingFindings").asInt())
                .isEqualTo(expectedActionable - 1);
        assertThat(human.get("escalated").asInt()).isEqualTo(1);
        assertThat(human.get("readyToFinalize").asBoolean())
                .as("an active escalation blocks finalization")
                .isFalse();
        assertThat(blockerCodes(human)).contains("ACTIVE_ESCALATION");

        assertThat(human.get("status").asText()).isEqualTo(humanReviewEndpointStatus(
                fixture.review().id()));
        assertThat(human.get("readyToFinalize").asBoolean()).isEqualTo(
                humanReviewEndpointReady(fixture.review().id()));
        assertThat(human.get("resolvedFindings").asInt()).isEqualTo(
                humanReviewEndpointInt(fixture.review().id(), "resolvedFindings"));
        assertThat(human.get("actionableFindings").asInt()).isEqualTo(
                humanReviewEndpointInt(fixture.review().id(), "actionableFindings"));
    }

    /**
     * R2 for the other end of the lifecycle: a fully decided review with no escalation
     * is genuinely ready to finalize, and the row says so using the server's own
     * readiness flag rather than a percentage.
     */
    @Test
    void aFullyDecidedReviewBecomesReadyToFinalize() throws Exception {
        var fixture = analyzedMainCorpus();
        for (var finding : distinctActionable(fixture)) {
            decide(fixture.review().id(), finding.id(),
                    "{\"expectedVersion\": 0, \"action\": \"REJECT\","
                            + " \"comment\": \"Not applicable.\"}");
        }

        var human = rowFor(fixture.review().id()).get("humanReview");

        assertThat(human.get("remainingFindings").asInt()).isZero();
        assertThat(human.get("readyToFinalize").asBoolean()).isTrue();
        assertThat(human.get("status").asText()).isEqualTo("READY_FOR_COMPLETION");
        assertThat(human.get("readyToFinalize").asBoolean()).isEqualTo(
                humanReviewEndpointReady(fixture.review().id()));
    }

    /**
     * R2 before analysis has produced anything: a queued review is not ready and says
     * why, instead of reporting zeroes that read as "nothing to do".
     */
    @Test
    void aQueuedReviewIsNotReadyAndSaysAnalysisIsNotComplete() throws Exception {
        var fixture = analyzedMainCorpus();
        var queued = queuedReview();

        var human = rowFor(queued.id()).get("humanReview");

        assertThat(human.get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(human.get("readyToFinalize").asBoolean()).isFalse();
        assertThat(blockerCodes(human)).contains("ANALYSIS_NOT_COMPLETE");
        assertThat(blockerCodes(rowFor(fixture.review().id()).get("humanReview")))
                .as("the analysed review in the same page is unready for its own reason, "
                        + "so the two rows are not interchangeable")
                .doesNotContain("ANALYSIS_NOT_COMPLETE")
                .isNotEqualTo(blockerCodes(human));
    }

    /**
     * R3: finalization is its own fact, separate from analysis completion. Analysis
     * completing must not read as finalized, and the approved-change count must be the
     * count of the frozen set rather than the number of findings.
     */
    @Test
    void finalizationIsDistinctFromAnalysisCompletionAndCountsApprovedChanges()
            throws Exception {
        var fixture = analyzedMainCorpus();
        var analyzed = rowFor(fixture.review().id());
        assertThat(analyzed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(analyzed.get("finalization").isNull())
                .as("a completed analysis is not a finalized review, and says nothing "
                        + "about finalization rather than reporting an empty one")
                .isTrue();

        var finding = findingWith(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        var set = finalizationService.finalizeReview(fixture.review().id());

        var finalized = rowFor(fixture.review().id()).get("finalization");
        assertThat(finalized.get("finalizedAt").asText())
                .isEqualTo(set.finalizedAt().toString());
        assertThat(finalized.get("approvedChanges").asInt())
                .as("the count is the frozen change set, not the finding count")
                .isEqualTo(set.changes().size());
        assertThat(finalized.get("approvedChanges").asInt()).isPositive();
    }

    /**
     * R3 for a finalized review with nothing approved: the count is genuinely zero and
     * that is a real outcome, not a missing value.
     */
    @Test
    void aFinalizedReviewWithNoApprovedChangesReportsZero() throws Exception {
        var fixture = analyzedMainCorpus();
        for (var finding : distinctActionable(fixture)) {
            decide(fixture.review().id(), finding.id(),
                    "{\"expectedVersion\": 0, \"action\": \"REJECT\","
                            + " \"comment\": \"Not applicable.\"}");
        }
        finalizationService.finalizeReview(fixture.review().id());

        var finalization = rowFor(fixture.review().id()).get("finalization");

        assertThat(finalization.get("finalizedAt").isNull()).isFalse();
        assertThat(finalization.get("approvedChanges").asInt()).isZero();
    }

    /**
     * R4: only a genuinely verified redline reports a verified-change count. A failed
     * execution keeps its count absent even though partial per-change verification may
     * exist internally, so a partially successful redline can never be rendered as a
     * verified one.
     */
    @Test
    void aVerifiedRedlineReportsItsVerifiedCount() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = findingWith(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        var set = finalizationService.finalizeReview(fixture.review().id());
        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isOk());

        var redline = rowFor(fixture.review().id()).get("redline");

        assertThat(redline.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(redline.get("outcome").asText()).isEqualTo("REDLINE_GENERATED");
        assertThat(redline.get("verifiedChangeCount").asInt())
                .isEqualTo(set.changes().size());
        assertThat(redline.get("downloadAvailable").asBoolean()).isTrue();
    }

    /**
     * R4 when no redline was ever requested. Absence is reported as absence, not as a
     * zero-count success.
     */
    @Test
    void aFinalizedReviewWithoutARedlineReportsNoRedline() throws Exception {
        var fixture = analyzedMainCorpus();
        for (var finding : distinctActionable(fixture)) {
            decide(fixture.review().id(), finding.id(),
                    "{\"expectedVersion\": 0, \"action\": \"REJECT\","
                            + " \"comment\": \"Not applicable.\"}");
        }
        finalizationService.finalizeReview(fixture.review().id());

        var redline = rowFor(fixture.review().id()).get("redline");

        assertThat(redline.isNull())
                .as("redline was never requested, which is not the same as a redline of zero")
                .isTrue();
    }

    /**
     * R4 where redline ran and there was nothing to redline. That is a real, completed
     * execution and must stay distinguishable from both a verified artifact and from a
     * redline that was never requested.
     */
    @Test
    void aNoChangesRedlineIsReportedWithoutAVerifiedCount() throws Exception {
        var fixture = analyzedMainCorpus();
        for (var finding : distinctActionable(fixture)) {
            decide(fixture.review().id(), finding.id(),
                    "{\"expectedVersion\": 0, \"action\": \"REJECT\","
                            + " \"comment\": \"Not applicable.\"}");
        }
        finalizationService.finalizeReview(fixture.review().id());
        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isOk());

        var redline = rowFor(fixture.review().id()).get("redline");

        assertThat(redline.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(redline.get("outcome").asText()).isEqualTo("NO_CHANGES");
        assertThat(redline.get("verifiedChangeCount").isNull())
                .as("nothing was verified, so no verified count may be implied")
                .isTrue();
        assertThat(redline.get("downloadAvailable").asBoolean()).isFalse();
    }

    /**
     * R1 to R5 together, on one page, so the projection is proven to be composed rather
     * than assembled per row from follow-up requests.
     */
    @Test
    void oneRowCarriesEveryCanonicalFieldAtOnce() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = findingWith(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        finalizationService.finalizeReview(fixture.review().id());

        var row = rowFor(fixture.review().id());

        assertThat(row.fieldNames()).toIterable().contains(
                "id", "contractId", "contractVersionNumber", "contractFilename",
                "uploadedAt", "status", "context", "humanReview", "finalization",
                "redline", "findings", "createdAt");
        assertThat(row.get("humanReview").get("readyToFinalize").asBoolean())
                .as("a finalized review is no longer ready to finalize")
                .isFalse();
    }

    /**
     * Tenant isolation: a second organization's review never appears, and never
     * contributes another organization's counts to a shared batch.
     */
    @Test
    void anotherOrganizationsReviewIsInvisible() throws Exception {
        var mine = analyzedMainCorpus();
        assertThat(allRowIds()).contains(mine.review().id());

        var myOrganization = identityProvider.currentIdentity().organizationId();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                Set.of("ADMIN")));
        var theirs = analyzedMainCorpus();

        var theirIds = allRowIds();
        assertThat(theirIds).contains(theirs.review().id());
        assertThat(theirIds)
                .as("another organization's review is indistinguishable from missing")
                .doesNotContain(mine.review().id());
        assertThat(rowForOrNull(mine.review().id())).isNull();
        assertThat(rowForOrNull(theirs.review().id())).isNotNull();

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), myOrganization, Set.of("ADMIN")));
        assertThat(allRowIds()).contains(mine.review().id())
                .doesNotContain(theirs.review().id());
    }

    /**
     * The existing list guarantees must survive the widening: newest-first ordering, a
     * stable cursor, and no row repeated or skipped across pages.
     */
    @Test
    void cursorPagingStillCoversEveryRowExactlyOnce() throws Exception {
        var created = new java.util.ArrayList<UUID>();
        for (var i = 0; i < 5; i++) {
            var bytes = Files.readAllBytes(Path.of(
                    "src/test/resources/fixtures/m5-nda-counterparty.docx"));
            var ingested = contractIngestionService.ingest("paged-" + i + ".docx",
                    ContractIngestionService.DOCX_CONTENT_TYPE,
                    new ByteArrayInputStream(bytes));
            enrichmentService.enrich(ingested.contractId(), 1,
                    ReviewProfile.minimal(ContractType.NDA));
            created.add(reviewService.create(
                    ingested.contractId(), 1, null, null).id());
        }

        var seen = new java.util.ArrayList<UUID>();
        String cursor = null;
        var pages = 0;
        do {
            var page = page(cursor, 2);
            for (var item : page.get("items")) {
                seen.add(UUID.fromString(item.get("id").asText()));
            }
            cursor = page.has("nextCursor") && !page.get("nextCursor").isNull()
                    ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
        assertThat(seen).doesNotHaveDuplicates();
    }

    private JsonNode page(String cursor, int limit) throws Exception {
        var url = "/api/reviews?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
        var result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json(result).get("data");
    }

    private List<UUID> allRowIds() throws Exception {
        var result = mockMvc.perform(get("/api/reviews?limit=100"))
                .andExpect(status().isOk()).andReturn();
        var items = json(result).get("data").get("items");
        var ids = new java.util.ArrayList<UUID>();
        items.forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private JsonNode rowFor(UUID reviewId) throws Exception {
        var row = rowForOrNull(reviewId);
        assertThat(row).as("review %s must appear in the list", reviewId).isNotNull();
        return row;
    }

    private JsonNode rowForOrNull(UUID reviewId) throws Exception {
        for (var item : allRows()) {
            if (item.get("id").asText().equals(reviewId.toString())) {
                return item;
            }
        }
        return null;
    }

    private List<JsonNode> allRows() throws Exception {
        var result = mockMvc.perform(get("/api/reviews?limit=100"))
                .andExpect(status().isOk()).andReturn();
        var items = json(result).get("data").get("items");
        var rows = new java.util.ArrayList<JsonNode>();
        items.forEach(rows::add);
        return rows;
    }

    private List<String> blockerCodes(JsonNode human) {
        var codes = new java.util.ArrayList<String>();
        human.get("blockers").forEach(blocker -> codes.add(blocker.asText()));
        return codes;
    }

    private JsonNode humanReviewEndpoint(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/human-review", reviewId))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("data");
    }

    private String humanReviewEndpointStatus(UUID reviewId) throws Exception {
        return humanReviewEndpoint(reviewId).get("status").asText();
    }

    private boolean humanReviewEndpointReady(UUID reviewId) throws Exception {
        return humanReviewEndpoint(reviewId).get("readyToFinalize").asBoolean();
    }

    private int humanReviewEndpointInt(UUID reviewId, String field) throws Exception {
        return humanReviewEndpoint(reviewId).get(field).asInt();
    }

    private String jdbcInstant(String sql, UUID id) {
        return jdbcClient.sql(sql)
                .param("id", id)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant()
                .toString();
    }

    private record Fixture(Review review, ReviewAnalysisRun run, List<Finding> findings) {
    }

    private Fixture analyzedMainCorpus() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    private Fixture analyzedCarveOutConflict() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m7-msa-carveout-conflict.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-carveout-conflict.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1, new ReviewProfile(
                ContractType.MSA, PartyRole.CUSTOMER, PaperSource.THIRD_PARTY,
                Jurisdiction.US, ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(
                ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    /** A review that exists but whose analysis has not been run. */
    private Review queuedReview() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        return reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
    }

    /**
     * Actionable findings, deduplicated by id.
     *
     * <p>The finding read materialises child rows, so one finding can appear more than
     * once. Deciding it twice is a version conflict rather than a second decision, which
     * would make these tests assert on the wrong thing.
     */
    private List<Finding> distinctActionable(Fixture fixture) {
        return fixture.findings().stream()
                .filter(f -> f.reviewClassification() != FindingClassification.INFORMATIONAL)
                .collect(java.util.stream.Collectors.toMap(
                        Finding::id, f -> f, (first, second) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private Finding findingWith(Fixture fixture, ProposedAction action) {
        return fixture.findings().stream()
                .filter(finding -> finding.proposedAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding " + action));
    }

    private void decide(UUID reviewId, UUID findingId, String body) throws Exception {
        var result = mockMvc.perform(put(
                        "/api/reviews/{reviewId}/findings/{findingId}/decision",
                        reviewId, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("deciding %s with %s: %s", findingId, body,
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;
}