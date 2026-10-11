package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.retrieval.api.RetrievalResponses;

/**
 * The per-finding position comparison must be a faithful mirror of the finding's own
 * frozen evidence. These tests never assert which roles the corpus happens to publish;
 * they assert that whatever the evidence says, the projection says the same thing in the
 * same order, and that a slot with no evidence stays empty instead of being filled in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class FindingPositionComparisonIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String CREATE_PATH =
            "/api/contracts/{contractId}/versions/{versionNumber}/reviews";

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
    ReviewAnalysisService analysisService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void comparisonMirrorsTheFindingsFrozenEvidenceExactly() throws Exception {
        var detail = analyzedFindingDetail();

        var evidence = detail.get("evidenceItems");
        var comparison = detail.get("positionComparison");
        assertThat(comparison).isNotNull();

        assertThat(slotIds(comparison.get("preferred")))
                .containsExactlyElementsOf(
                        evidenceIds(withRole(evidence, PositionRole.PREFERRED)));
        assertThat(slotIds(comparison.get("fallback")))
                .containsExactlyElementsOf(
                        evidenceIds(withRole(evidence, PositionRole.FALLBACK)));
        assertThat(slotIds(comparison.get("escalation")))
                .containsExactlyElementsOf(evidenceIds(withTrigger(evidence)));
        assertThat(slotIds(comparison.get("contract")))
                .containsExactlyElementsOf(evidenceIds(withContractFacts(evidence)));

        comparison.get("preferred").forEach(slot -> {
            assertThat(slot.get("text").asText()).isNotBlank();
            assertThat(slot.get("authorityClass").asText()).isEqualTo("POLICY");
            assertThat(slot.get("publicationNumber").isNull()).isFalse();
        });
        comparison.get("fallback").forEach(slot -> {
            assertThat(slot.get("text").asText()).isNotBlank();
            assertThat(slot.get("authorityClass").asText()).isEqualTo("POLICY");
        });
        comparison.get("escalation").forEach(slot ->
                assertThat(slot.get("trigger").asText()).isNotBlank());
        comparison.get("contract").forEach(slot -> {
            assertThat(slot.get("authorityClass").asText()).isEqualTo("CURRENT_CONTRACT");
            assertThat(slot.get("facts").isEmpty()).isFalse();
            slot.get("facts").forEach(fact -> {
                assertThat(fact.get("factSchema").asText()).isNotBlank();
                assertThat(fact.get("values").isEmpty()).isFalse();
            });
        });
    }

    @Test
    void aRoleTheEvidenceDoesNotCarryStaysEmpty() throws Exception {
        var evidence = analyzedFindingDetail().get("evidenceItems");
        var comparison = analyzedFindingDetail().get("positionComparison");

        if (withRole(evidence, PositionRole.PREFERRED).isEmpty()) {
            assertThat(comparison.get("preferred")).isEmpty();
        }
        if (withRole(evidence, PositionRole.FALLBACK).isEmpty()) {
            assertThat(comparison.get("fallback")).isEmpty();
        }
        if (withTrigger(evidence).isEmpty()) {
            assertThat(comparison.get("escalation")).isEmpty();
        }
        if (withContractFacts(evidence).isEmpty()) {
            assertThat(comparison.get("contract")).isEmpty();
        }
    }

    @Test
    void rolesNamedOnlyInTheAuthoredProseAreNotPromotedIntoSlots() throws Exception {
        var detail = analyzedFindingDetail();
        var evidence = detail.get("evidenceItems");
        var comparison = detail.get("positionComparison");

        for (var item : evidence) {
            var text = item.path("text").asText();
            var classified = item.path("policyFacts").path("positions");
            if (!classified.isArray()) {
                continue;
            }
            if (mentionsRole(text, "Prohibited")
                    && !hasPosition(item, PositionRole.PROHIBITED)) {
                assertThat(comparison.get("fallback")).isEmpty();
            }
            if (mentionsRole(text, "Fallback")
                    && !hasPosition(item, PositionRole.FALLBACK)) {
                assertThat(comparison.get("fallback")).isEmpty();
            }
        }
    }

    private boolean mentionsRole(String text, String role) {
        return text.contains(role);
    }

    @Test
    void comparisonIsAbsentWhenNoEvidenceCarriesPositionData() {
        assertThat(FindingPositionComparison.from(List.of())).isNull();
        assertThat(FindingPositionComparison.from(null)).isNull();
    }

    @Test
    void oneEvidenceItemCanSupplyBothPolicySlotsWithoutBeingSplit() {
        var item = policyItem(Set.of(PositionRole.PREFERRED.name(), PositionRole.FALLBACK.name()),
                "Above net 90", 7L);

        var comparison = FindingPositionComparison.from(List.of(item));

        assertThat(comparison.preferred())
                .extracting(FindingPositionComparison.PolicyPositionSlot::evidenceItemId)
                .containsExactly(item.evidenceId());
        assertThat(comparison.fallback())
                .extracting(FindingPositionComparison.PolicyPositionSlot::evidenceItemId)
                .containsExactly(item.evidenceId());
        assertThat(comparison.escalation()).singleElement()
                .satisfies(slot -> assertThat(slot.trigger()).isEqualTo("Above net 90"));
    }

    @Test
    void blankEscalationTriggersAreNotReported() {
        var comparison = FindingPositionComparison.from(
                List.of(policyItem(Set.of(PositionRole.PREFERRED.name()), "   ", 7L)));

        assertThat(comparison.escalation()).isEmpty();
        assertThat(comparison.preferred()).hasSize(1);
    }

    @Test
    void contractSlotExposesTypedFactsRatherThanInventedProse() {
        var fact = new com.rivell.workbench.contracts.LegalFact(
                "termination-facts", 1, java.util.Map.of("noticePeriodDays", "90"));
        var item = new RetrievalResponses.EvidenceItemResponse(
                UUID.randomUUID(), "CURRENT_CONTRACT", "CURRENT_CONTRACT_CLAUSE", null, null,
                null, null, "TERMINATION", "Termination clause", null, List.of(fact),
                "CANONICAL_STRUCTURE", lane(), null, null, List.of(), null, null, null);

        var comparison = FindingPositionComparison.from(List.of(item));

        assertThat(comparison.contract()).singleElement().satisfies(slot -> {
            assertThat(slot.evidenceItemId()).isEqualTo(item.evidenceId());
            assertThat(slot.facts()).containsExactly(fact);
        });
        assertThat(comparison.preferred()).isEmpty();
        assertThat(comparison.fallback()).isEmpty();
    }

    private RetrievalResponses.EvidenceItemResponse policyItem(
            Set<String> positions, String trigger, Long publicationNumber) {
        return new RetrievalResponses.EvidenceItemResponse(
                UUID.randomUUID(), "POLICY", "PLAYBOOK_RULE", null, null, null, null,
                "LIMITATION_OF_LIABILITY", "Net 45 days", new RetrievalResponses.PolicyFactsResponse(
                        positions, null, trigger, null, List.of(), null), List.of(), "STRUCTURED", lane(), null, null,
                List.of(), null, publicationNumber, null);
    }

    private RetrievalResponses.LaneInfoResponse lane() {
        return new RetrievalResponses.LaneInfoResponse(null, null, null, null);
    }

    private JsonNode analyzedFindingDetail() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(), ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        var findingId = listFindings(reviewId).get(0).get("id").asText();
        return json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceItems").isNotEmpty())
                .andReturn()).get("data");
    }

    private List<JsonNode> withRole(JsonNode evidence, PositionRole role) {
        var matching = new ArrayList<JsonNode>();
        evidence.forEach(item -> {
            if (hasPosition(item, role)) {
                matching.add(item);
            }
        });
        return matching.stream().distinct().toList();
    }

    private boolean hasPosition(JsonNode item, PositionRole role) {
        var positions = item.path("policyFacts").path("positions");
        if (!positions.isArray()) {
            return false;
        }
        for (var position : positions) {
            if (role.name().equals(position.asText())) {
                return true;
            }
        }
        return false;
    }

    private List<JsonNode> withTrigger(JsonNode evidence) {
        var matching = new ArrayList<JsonNode>();
        evidence.forEach(item -> {
            var trigger = item.path("policyFacts").path("escalationTrigger");
            if (!trigger.isMissingNode() && !trigger.isNull() && !trigger.asText().isBlank()) {
                matching.add(item);
            }
        });
        return matching.stream().distinct().toList();
    }

    private List<JsonNode> withContractFacts(JsonNode evidence) {
        var matching = new ArrayList<JsonNode>();
        evidence.forEach(item -> {
            var facts = item.get("contractFacts");
            if (facts != null && facts.isArray() && !facts.isEmpty()) {
                matching.add(item);
            }
        });
        return matching.stream().distinct().toList();
    }

    private List<UUID> slotIds(JsonNode slots) {
        var ids = new ArrayList<UUID>();
        slots.forEach(node -> ids.add(UUID.fromString(node.get("evidenceItemId").asText())));
        return ids;
    }

    private List<UUID> evidenceIds(List<JsonNode> items) {
        return items.stream().map(item -> UUID.fromString(item.get("evidenceId").asText()))
                .toList();
    }

    private JsonNode createReview(UUID contractId, String packKey) throws Exception {
        var body = packKey == null
                ? "{}"
                : "{\"contractPackKey\":\"" + packKey + "\"}";
        var result = mockMvc.perform(post(CREATE_PATH, contractId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private JsonNode listFindings(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data").get("items");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}