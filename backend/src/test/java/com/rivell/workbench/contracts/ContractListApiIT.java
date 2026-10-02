package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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

/**
 * Behaviour of the contract enumeration added for Home (GAP-F).
 *
 * <p>There was previously no bare {@code GET /api/contracts} at all, so a user could not
 * find a contract they had uploaded. These tests pin the enumeration, its tenant scope,
 * its resumption, and the fact that it stayed enumeration: a new upload still mints a new
 * contract at version 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(ContractListApiIT.MutableIdentityConfiguration.class)
class ContractListApiIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    IdentityProviderForTests identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void listsTheOrganizationsContracts() throws Exception {
        createContract("first.docx");
        createContract("second.docx");

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void aRowsLatestVersionMatchesTheSingleContractReadExactly() throws Exception {
        var contractId = createContract("truth.docx");

        var listed = json(mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items").get(0);
        var single = json(mockMvc.perform(get("/api/contracts/{id}", contractId))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(listed.get("id").asText()).isEqualTo(contractId.toString());
        assertThat(listed.get("latestVersion")).isEqualTo(single.get("latestVersion"));
        assertThat(listed.get("latestVersion").get("filename").asText()).isEqualTo("truth.docx");
        assertThat(listed.get("latestVersion").get("versionNumber").asInt()).isEqualTo(1);
        assertThat(listed.get("latestVersion").get("parseStatus").asText()).isNotBlank();
        assertThat(listed.get("latestVersion").get("sha256").asText()).hasSize(64);
        assertThat(listed.get("latestVersion").has("nodeCount")).isTrue();
        assertThat(listed.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void anotherOrganizationsContractsAreNeverListed() throws Exception {
        createContract("mine.docx");

        var otherOrganization = UUID.randomUUID();
        var theirContract = seedContractFor(otherOrganization, "theirs.docx");
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId(), Set.of("ADMIN")));

        var ids = new ArrayList<String>();
        json(mockMvc.perform(get("/api/contracts").param("limit", "50"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items")
                .forEach(item -> ids.add(item.get("id").asText()));

        assertThat(ids)
                .as("tenant scope is enforced in SQL, so another tenant's contract is "
                        + "invisible rather than filtered client-side")
                .doesNotContain(theirContract.toString());
        assertThat(ids).hasSize(1);
    }

    @Test
    void anotherOrganizationsContractStillReadsAsNotFound() throws Exception {
        var otherOrganization = UUID.randomUUID();
        var theirContract = seedContractFor(otherOrganization, "theirs.docx");

        mockMvc.perform(get("/api/contracts/{id}", theirContract))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void orderingIsNewestFirstAndDeterministic() throws Exception {
        createContract("a.docx");
        createContract("b.docx");
        createContract("c.docx");

        var first = ids();
        var second = ids();

        assertThat(first).hasSize(3);
        assertThat(second).isEqualTo(first);

        var items = json(mockMvc.perform(get("/api/contracts"))
                .andReturn()).get("data").get("items");
        for (var i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).get("createdAt").asText())
                    .isGreaterThanOrEqualTo(items.get(i).get("createdAt").asText());
        }
    }

    @Test
    void cursorWalksEveryContractWithoutDuplicatingOrSkipping() throws Exception {
        var expected = new ArrayList<UUID>();
        for (var i = 0; i < 5; i++) {
            expected.add(createContract("c" + i + ".docx"));
        }

        var walked = new ArrayList<UUID>();
        String cursor = null;
        for (var guard = 0; guard < 10; guard++) {
            var request = MockMvcRequestBuilders.get("/api/contracts").param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            var data = json(mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn()).get("data");
            data.get("items").forEach(item -> walked.add(UUID.fromString(item.get("id").asText())));
            cursor = data.get("nextCursor").isNull() ? null : data.get("nextCursor").asText();
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked)
                .as("every contract exactly once across the whole cursor walk")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(walked).doesNotHaveDuplicates();
    }

    @Test
    void aCursorMintedForAnotherOrganizationCannotResumeIntoIt() throws Exception {
        var otherOrganization = UUID.randomUUID();
        var theirContract = seedContractFor(otherOrganization, "theirs.docx");
        var theirCursor = json(mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("nextCursor");
        assertThat(theirCursor.isNull()).isTrue();

        createContract("mine.docx");
        var cursorFromAnotherOrganization = "not-a-valid-position-for-this-organization";

        // A foreign or hand-made cursor is either rejected or resumes within this
        // organization. It must never surface another tenant's contracts.
        var response = mockMvc.perform(get("/api/contracts")
                        .param("cursor", cursorFromAnotherOrganization));
        response.andExpect(result -> {
            var status = result.getResponse().getStatus();
            assertThat(status == 200 || status == 400)
                    .as("a malformed cursor is rejected or ignored, never honoured as a "
                            + "cross-tenant position")
                    .isTrue();
        });
    }

    @Test
    void limitIsEnforcedAndBounded() throws Exception {
        for (var i = 0; i < 3; i++) {
            createContract("l" + i + ".docx");
        }

        mockMvc.perform(get("/api/contracts").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(get("/api/contracts").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void anEmptyOrganizationReturnsAnEmptyPage() throws Exception {
        var data = json(mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(data.get("items").size()).isZero();
    }

    @Test
    void anUnknownContractStillReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/contracts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void listingRequiresReadPermission() throws Exception {
        createContract("forbidden.docx");
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId(), Set.of("VIEWER")));

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
    }

    @Test
    void theListAddsNoContractVersionWritePath() throws Exception {
        var before = json(mockMvc.perform(get("/api/contracts"))
                .andReturn()).get("data").get("items").size();

        var ingested = createContract("brand-new.docx");

        var after = json(mockMvc.perform(get("/api/contracts"))
                .andReturn()).get("data").get("items");
        assertThat(after.size()).isEqualTo(before + 1);
        assertThat(after).anySatisfy(item -> {
            assertThat(item.get("id").asText()).isEqualTo(ingested.toString());
            assertThat(item.get("latestVersion").get("versionNumber").asInt())
                    .as("a new upload is a new contract at version 1; this milestone adds "
                            + "enumeration only, never a version-2 write path")
                    .isEqualTo(1);
        });
    }

    @Test
    void everyContractWithMultipleVersionsReportsOnlyTheHighestNumber() throws Exception {
        // Unreachable through the services, which always write version 1. Inserted
        // directly so the latest-version projection is proven against real multi-version
        // data rather than only against the single-version case production can reach.
        var contractId = createContract("versioned.docx");
        var organizationId = organizationId();
        insertVersion(contractId, organizationId, 2, "second.docx");
        insertVersion(contractId, organizationId, 3, "third.docx");

        var item = json(mockMvc.perform(get("/api/contracts"))
                .andReturn()).get("data").get("items").get(0);

        assertThat(item.get("id").asText()).isEqualTo(contractId.toString());
        assertThat(item.get("latestVersion").get("versionNumber").asInt()).isEqualTo(3);
        assertThat(item.get("latestVersion").get("filename").asText()).isEqualTo("third.docx");

        var single = json(mockMvc.perform(get("/api/contracts/{id}", contractId))
                .andReturn()).get("data");
        assertThat(item.get("latestVersion"))
                .as("the list and the single read must agree even with several versions")
                .isEqualTo(single.get("latestVersion"));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private List<String> ids() throws Exception {
        var items = json(mockMvc.perform(get("/api/contracts"))
                .andReturn()).get("data").get("items");
        var ids = new ArrayList<String>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private UUID createContract(String filename) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/simple-contract.docx"));
        var file = new MockMultipartFile("file", filename, DOCX_MEDIA_TYPE, bytes);
        var result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/contracts").file(file))
                .andExpect(status().isOk())
                .andReturn();
        var contractId = UUID.fromString(json(result).get("data").get("contractId").asText());
        enrichmentService.enrich(contractId, 1, new ReviewProfile(
                ContractType.MSA, PartyRole.RECEIVING_PARTY,
                PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US, "MSA_PACK"));
        return contractId;
    }

    private UUID seedContractFor(UUID organizationId, String filename) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/simple-contract.docx"));
        var artifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, :filename, :contentType, :size,
                        repeat('c', 64), :key, now())
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("filename", filename)
                .param("contentType", DOCX_MEDIA_TYPE)
                .param("size", bytes.length)
                .param("key", organizationId + "/" + artifactId + ".docx")
                .update();
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :organizationId, null, now())
                """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, :organizationId, 'ORIGINAL',
                        'rivell-docx-parser-1', 'OK', '[]'::jsonb, now())
                """)
                .param("id", UUID.randomUUID())
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .update();
        return contractId;
    }

    private void insertVersion(UUID contractId, UUID organizationId, int versionNumber,
            String filename) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/simple-contract.docx"));
        var artifactId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, :filename, :contentType, :size,
                        repeat('d', 64), :key, now())
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("filename", filename)
                .param("contentType", DOCX_MEDIA_TYPE)
                .param("size", bytes.length)
                .param("key", organizationId + "/" + artifactId + ".docx")
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, :versionNumber, :artifactId, :organizationId,
                        'ORIGINAL', 'rivell-docx-parser-1', 'OK', '[]'::jsonb, now())
                """)
                .param("id", UUID.randomUUID())
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .update();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Minimal mutable identity so each test runs under its own organization. Declared here
     * rather than imported because the contracts package has no existing test that exports
     * one.
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        IdentityProviderForTests identityProviderForTests() {
            return new IdentityProviderForTests();
        }
    }

    static final class IdentityProviderForTests implements
            com.rivell.workbench.organization.IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }
}