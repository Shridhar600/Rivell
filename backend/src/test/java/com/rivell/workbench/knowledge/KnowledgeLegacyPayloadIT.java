package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Reviewer finding 3: rows written before column order was recorded must keep working.
 *
 * <p>The order is carried in the payload because {@code jsonb} does not preserve object key order
 * - it returns keys ordered by length then bytes, so a row keyed Clause/Preferred/Fallback comes
 * back Clause/Fallback/Preferred. Those legacy rows recorded no order at all, and rendering a
 * corrected column for them would have restated the spreadsheet row with its values transposed.
 * The machine wording is the reliable record of source order: extraction rendered it left to
 * right, so the order is recovered from it and verified against the stored values.
 *
 * <p>The rows here are inserted in the pre-M10B-B6 shape on purpose, so this fails against any
 * implementation that only understands rows written after the fix.
 */
@SpringBootTest
@Testcontainers
class KnowledgeLegacyPayloadIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String MACHINE_ROW = "Liability cap | 1x annual fees | 2x annual fees";
    private static final String CORRECTED_ROW = "Liability cap | 3x annual fees | 2x annual fees";

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

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        IdentityProvider mutableIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Set.of("ADMIN"));

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableIdentityProvider identityProvider;

    private UUID organizationId;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
        organizationId = identityProvider.current.organizationId();
    }

    @Test
    void aLegacyRowRecoversSourceOrderAndCorrectsInThatOrder() {
        var candidateId = insertLegacyCandidate(null);

        var reloaded = queryService.getCandidate(candidateId);
        assertThat(reloaded.payload().orderedKeys())
                .as("the source order must be recovered, not the database's key order")
                .containsExactly("Clause", "Preferred", "Fallback");

        var correctedColumns = new LinkedHashMap<>(reloaded.payload().columns());
        correctedColumns.put("Preferred", "3x annual fees");
        var corrected = reviewService.edit(candidateId, null,
                new CandidatePayload(correctedColumns), null, null, "raise the preferred cap");

        assertThat(corrected.effectiveContentText())
                .as("a legacy row must still publish the correction in source column order")
                .isEqualTo(CORRECTED_ROW);
        assertThat(queryService.getCandidate(candidateId).effectiveContentText())
                .as("the recovered order must survive a reload")
                .isEqualTo(CORRECTED_ROW);
    }

    /**
     * A legacy row that already carries a correction: its values no longer match the machine
     * wording, so the order is recovered from the machine payload's columns instead. The
     * correction must still read left to right.
     */
    @Test
    void aLegacyRowWithAnExistingCorrectionRendersInSourceOrder() {
        var correctedPayload = """
                {"Clause":"Liability cap","Preferred":"3x annual fees","Fallback":"2x annual fees"}
                """;
        var candidateId = insertLegacyCandidate(correctedPayload);

        var reloaded = queryService.getCandidate(candidateId);
        assertThat(reloaded.effectiveContentText())
                .as("a legacy correction must become effective in source order, not key order")
                .isEqualTo(CORRECTED_ROW);
        assertThat(reloaded.editedPayload().orderedKeys())
                .containsExactly("Clause", "Preferred", "Fallback");
    }

    /**
     * The wording invariant on a legacy row: a blank human wording must not blank or null a
     * candidate that has valid machine wording.
     */
    @Test
    void aLegacyRowWithBlankHumanWordingFallsBackToTheMachineWording() {
        var candidateId = insertLegacyCandidate(null);
        jdbc.sql("update knowledge_candidate set edited_content_text = '   ' where id = :id")
                .param("id", candidateId)
                .update();

        assertThat(queryService.getCandidate(candidateId).effectiveContentText())
                .as("a blank layer must be skipped, not accepted as the wording")
                .isEqualTo(MACHINE_ROW);
    }

    /**
     * Recovery must never invent an order the source does not support. When the stored wording is
     * not the rendering of the stored values - here an unrelated sentence - there is nothing
     * reliable to recover, and the payload keeps the order the database returned rather than
     * claiming a source order it cannot prove.
     */
    @Test
    void anUnrelatedStoredWordingDoesNotImposeAnOrder() {
        var candidateId = insertLegacyCandidate(null);
        jdbc.sql("update knowledge_candidate set content_text = :text where id = :id")
                .param("text", "Liability shall be limited as agreed between the parties.")
                .param("id", candidateId)
                .update();

        var reloaded = queryService.getCandidate(candidateId);
        assertThat(reloaded.effectiveContentText())
                .as("machine wording that is not the rendering of the columns is still the wording")
                .isEqualTo("Liability shall be limited as agreed between the parties.");
        assertThat(reloaded.payload().orderedKeys())
                .as("no order is claimed when none can be derived from the source")
                .containsExactly("Clause", "Fallback", "Preferred");
    }

    private UUID insertLegacyCandidate(String editedPayload) {
        var artifactId = UUID.randomUUID();
        jdbc.sql("""
                insert into knowledge_artifact (
                  id, organization_id, artifact_type, filename, content_type, size_bytes,
                  sha256, storage_key, declared_contract_types, ingest_status,
                  parse_warnings, created_at)
                values (
                  :id, :organizationId, 'PLAYBOOK', 'legacy-matrix.xlsx',
                  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1024,
                  :sha256, 'legacy/legacy-matrix.xlsx', '[]'::jsonb, 'EXTRACTED',
                  '[]'::jsonb, now())
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("sha256", "0".repeat(64))
                .update();

        var unitId = UUID.randomUUID();
        jdbc.sql("""
                insert into knowledge_extraction_unit (
                  id, artifact_id, organization_id, ordinal, kind, provenance, created_at)
                values (:id, :artifactId, :organizationId, 1, 'SPREADSHEET_ROW',
                  '{"memberNodeIds":[]}'::jsonb, now())
                """)
                .param("id", unitId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .update();

        var candidateId = UUID.randomUUID();
        jdbc.sql("""
                insert into knowledge_candidate (
                  id, artifact_id, organization_id, extraction_unit_id, candidate_type,
                  review_status, raw_label, content_text, payload, edited_content_text,
                  edited_payload, declared_contract_types, conflict_signals, created_at, updated_at)
                values (
                  :id, :artifactId, :organizationId, :unitId, 'PLAYBOOK_RULE',
                  'PENDING', 'Liability cap', :contentText,
                  '{"Clause":"Liability cap","Preferred":"1x annual fees","Fallback":"2x annual fees"}'::jsonb,
                  null, :editedPayload::jsonb, '[]'::jsonb, '[]'::jsonb,
                  now(), now())
                """)
                .param("id", candidateId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("unitId", unitId)
                .param("contentText", MACHINE_ROW)
                .param("editedPayload", editedPayload)
                .update();
        return candidateId;
    }
}
