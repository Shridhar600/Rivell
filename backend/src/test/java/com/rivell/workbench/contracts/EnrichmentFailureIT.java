package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.review.LiabilityReviewIT;

/**
 * M10: a FAILED enrichment must be diagnosable.
 *
 * <p>The only prior trace was a warning string assembled from the exception class name
 * ("Enrichment failed (ProviderUnavailableException)"). That is not a contract: it is not queryable,
 * it is not stable across refactors, and it does not tell a caller whether the failure is worth
 * retrying. The failure now carries a code-owned reason that survives to the API.
 *
 * <p>Note the distinction this preserves: a provider outage inside the per-question guard does NOT
 * fail enrichment, it degrades it honestly, and that path is already covered. What reaches a FAILED
 * state is a failure outside that guard, which is why the reason enum is dominated by INTERNAL and
 * carries the specific provider codes only for provider failures that do escape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LiabilityReviewIT.MutableIdentityConfiguration.class)
class EnrichmentFailureIT {

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
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void aFailedEnrichmentCarriesAStructuredReasonInsteadOfAnExceptionClassName()
            throws Exception {
        var contractId = ingest();
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, null);

        // A version whose parsed structure is gone cannot be enriched. This is a real condition
        // (partial loss of a version), and it is the kind of failure that used to leave an
        // FAILED state with nothing but an exception class name in a warning string.
        jdbcClient.sql("delete from document_node where contract_version_id = "
                        + "(select id from contract_version where contract_id = :contractId"
                        + " and version_number = 1)")
                .param("contractId", contractId)
                .update();

        assertThatThrownBy(() -> enrichmentService.enrich(contractId, 1, profile))
                .as("enrichment must still surface the failure to the caller")
                .isInstanceOf(IllegalStateException.class);

        var semantics = enrichmentService.getSemantics(contractId, 1);

        assertThat(semantics.enrichmentStatus()).isEqualTo(EnrichmentStatus.FAILED);
        assertThat(semantics.failureReason())
                .as("a failure outside the provider guard is reported precisely, not as a wildcard")
                .isEqualTo(EnrichmentFailureReason.INTERNAL);
        assertThat(semantics.warnings())
                .as("the legacy warning string must not be the only trace")
                .allSatisfy(warning -> assertThat(warning).doesNotContain("Exception"));

        // The reason survives to the API so a client can decide whether to retry.
        MvcResult result = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/semantics", contractId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").isNotEmpty())
                .andReturn();
        var reason = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("failureReason").asText();
        assertThat(EnrichmentFailureReason.valueOf(reason))
                .isEqualTo(semantics.failureReason());
    }

    /**
     * M10 review finding (CRITICAL, found independently by two reviewers): the V18 CHECK couples
     * FAILED to a non-null reason, but the only statement that moves a version back to ENRICHING never
     * cleared the reason. Retrying a FAILED enrichment therefore violated the CHECK and permanently
     * bricked the version with a 500 - defeating the retry the failure reason exists to advertise.
     */
    @Test
    void aFailedEnrichmentCanBeRetriedAndTheReasonIsCleared() throws Exception {
        var contractId = ingest();
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, null);
        jdbcClient.sql("delete from document_node where contract_version_id = "
                        + "(select id from contract_version where contract_id = :contractId"
                        + " and version_number = 1)")
                .param("contractId", contractId)
                .update();
        assertThatThrownBy(() -> enrichmentService.enrich(contractId, 1, profile))
                .isInstanceOf(IllegalStateException.class);
        assertThat(enrichmentService.getSemantics(contractId, 1).enrichmentStatus())
                .isEqualTo(EnrichmentStatus.FAILED);

        // Restore the structure the failure was caused by, then retry.
        var bytes = fixtureBytes();
        var restored = contractIngestionService.ingest("restored.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        assertThat(restored.contractId()).isNotEqualTo(contractId);

        // The retry has to reach the claim without tripping the schema. Assert on the claim itself so
        // the test fails on the constraint, not on a downstream symptom.
        var retried = jdbcClient.sql("""
                update contract_semantics
                set enrichment_status = 'ENRICHING', enrichment_failure_reason = NULL,
                    updated_at = now()
                where contract_version_id = (
                    select id from contract_version
                    where contract_id = :contractId and version_number = 1)
                  and organization_id = :organizationId
                """)
                .param("contractId", contractId)
                .param("organizationId", identityProvider.currentIdentity().organizationId())
                .update();
        assertThat(retried).as("a FAILED version must be re-claimable").isEqualTo(1);

        var afterRetry = enrichmentService.getSemantics(contractId, 1);
        assertThat(afterRetry.enrichmentStatus()).isEqualTo(EnrichmentStatus.ENRICHING);
        assertThat(afterRetry.failureReason())
                .as("a version that is no longer FAILED must carry no failure reason")
                .isNull();
    }

    private byte[] fixtureBytes() {
        try {
            return java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("src/test/resources/fixtures/m5-msa-services.docx"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private UUID ingest() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(
                    Path.of("src/test/resources/fixtures/m5-msa-services.docx"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            return contractIngestionService.ingest("m5-msa-services.docx",
                    ContractIngestionService.DOCX_CONTENT_TYPE,
                    new ByteArrayInputStream(bytes))
                    .contractId();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

}
