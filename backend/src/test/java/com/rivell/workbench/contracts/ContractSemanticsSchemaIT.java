package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class ContractSemanticsSchemaIT {

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
    JdbcClient jdbcClient;

    @Autowired
    ContractSemanticsRepository semanticsRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void appliesContractSemanticsMigration() {
        for (var table : List.of("contract_semantics", "contract_metadata_fact",
                "contract_party", "contract_defined_term", "contract_reference",
                "contract_clause_semantics", "contract_relationship")) {
            assertThat(tableExists(table)).isTrue();
        }
    }

    @Test
    void rejectsSemanticsBoundToForeignOrganization() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);

        var semantics = new ContractSemantics(UUID.randomUUID(), versionId, UUID.randomUUID(),
                EnrichmentStatus.ENRICHING, ContractSemantics.PIPELINE_VERSION,
                ReviewProfile.minimal(ContractType.NDA), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), QualityReasons.NONE, null, List.of(), null);

        assertThatThrownBy(() -> semanticsRepository.replaceSemantics(semantics))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void beginEnrichmentIsAtomicAndRecoversStaleRuns() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);

        assertThat(semanticsRepository.beginEnrichment(versionId, organizationId,
                ReviewProfile.minimal(ContractType.NDA))).isTrue();
        assertThat(semanticsRepository.beginEnrichment(versionId, organizationId,
                ReviewProfile.minimal(ContractType.NDA))).isFalse();

        // a run abandoned >10 minutes ago is recoverable
        jdbcClient.sql("""
                update contract_semantics set updated_at = now() - interval '11 minutes'
                where contract_version_id = :versionId
                """)
                .param("versionId", versionId).update();
        assertThat(semanticsRepository.beginEnrichment(versionId, organizationId,
                ReviewProfile.minimal(ContractType.NDA))).isTrue();
    }

    @Test
    void replaceKeepsStableHeaderIdAndFullyReplacesChildren() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);
        var headingId = insertNode(versionId, null, "HEADING", "Limitation of Liability", "6");

        transactionTemplate.executeWithoutResult(status -> {
            semanticsRepository.beginEnrichment(versionId, organizationId,
                    ReviewProfile.minimal(ContractType.MSA));
            semanticsRepository.replaceSemantics(semantics(
                    UUID.randomUUID(), organizationId, versionId, headingId,
                    EnrichmentStatus.READY, "v1", "old-machine-value"));
        });
        var headerAfterFirstRun = jdbcClient.sql(
                        "select id from contract_semantics where contract_version_id = :versionId")
                .param("versionId", versionId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .single();
        var clauseCount = jdbcClient.sql(
                        "select count(*) from contract_clause_semantics where contract_version_id = :versionId")
                .param("versionId", versionId)
                .query(Integer.class).single();
        assertThat(clauseCount).isEqualTo(1);

        transactionTemplate.executeWithoutResult(status ->
                semanticsRepository.replaceSemantics(semantics(
                        UUID.randomUUID(), organizationId, versionId, headingId,
                        EnrichmentStatus.READY, "rivell-contract-enrichment-2",
                        "new-machine-value")));

        var headerAfterSecondRun = jdbcClient.sql(
                        "select id from contract_semantics where contract_version_id = :versionId")
                .param("versionId", versionId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .single();
        assertThat(headerAfterSecondRun).isEqualTo(headerAfterFirstRun);

        var clauseCountAfterRerun = jdbcClient.sql(
                        "select count(*) from contract_clause_semantics where contract_version_id = :versionId")
                .param("versionId", versionId)
                .query(Integer.class).single();
        assertThat(clauseCountAfterRerun).isEqualTo(1);

        var reloaded = semanticsRepository.find(versionId, organizationId).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(headerAfterFirstRun);
        assertThat(reloaded.clauses().get(0).contentText()).isEqualTo("new-machine-value");
        assertThat(reloaded.enrichmentStatus()).isEqualTo(EnrichmentStatus.READY);
    }

    @Test
    void storesAndReloadsFullSemanticContract() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);
        var headingId = insertNode(versionId, null, "HEADING", "Limitation of Liability", "6");
        var textId = insertNode(versionId, headingId, "PARAGRAPH",
                "Supplier liability shall not exceed three times the Fees paid in the twelve months immediately preceding the claim.",
                null);
        var targetHeadingId = insertNode(versionId, null, "HEADING", "Indemnification", "7");

        var machineInterpretation = new Interpretation("primaryConcept",
                InterpretationKind.BOUNDED_CLASSIFICATION, "LIMITATION_OF_LIABILITY",
                "contract-clause-legal-concept", 1, UUID.randomUUID(),
                List.of(textId), VerificationState.NOT_VERIFIED, null);
        var party = new ContractParty(UUID.randomUUID(), versionId,
                "Northwind Customer Group LLC", "northwind customer group llc",
                List.of("Northwind"),
                List.of(Interpretation.deterministic("rawName", "Northwind Customer Group LLC",
                        List.of(textId))),
                List.of(PartyRole.CUSTOMER, PartyRole.PARTY_A), PartyCorrection.NONE, null);
        var metadata = new MetadataFact(UUID.randomUUID(), versionId, MetadataField.EFFECTIVE_DATE,
                "2026-01-01", Interpretation.deterministic("effectiveDate", "2026-01-01",
                        List.of(textId)), null, null);
        var definedTerm = new DefinedTerm(UUID.randomUUID(), versionId, "Fees",
                "the fees set out in Schedule A", headingId, List.of(textId), List.of(textId),
                false);
        var reference = new ContractReference(UUID.randomUUID(), versionId, textId,
                "Section 7", ReferenceKind.SECTION, ReferenceStatus.RESOLVED,
                targetHeadingId, List.of());
        var clause = new ClauseSemantics(UUID.randomUUID(), versionId, headingId,
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE,                List.of(textId),
                "Supplier liability shall not exceed three times the Fees paid in the twelve months immediately preceding the claim.",
                new ConceptClassification(List.of(LegalConcept.LIMITATION_OF_LIABILITY),
                        List.of(), Map.of(LegalConcept.LIMITATION_OF_LIABILITY, 0.93)),
                List.of(new LegalFact("liability-facts", 1, Map.of(
                        "liabilityCapMultiple", "3",
                        "capBasis", "PRECEDING_12_MONTH_FEES",
                        "consequentialDamagesExcluded", "true"))),
                List.of(machineInterpretation), FactCorrection.NONE, null);
        var relationship = new SemanticRelationship(UUID.randomUUID(), versionId,
                headingId, targetHeadingId, RelationshipType.CARVES_OUT,
                RelationshipDetector.SEMANTIC, "explicit carve-out language", 0.86,
                machineInterpretation.executionId(), VerificationState.NOT_VERIFIED, null);
        var semantics = new ContractSemantics(UUID.randomUUID(), versionId, organizationId,
                EnrichmentStatus.READY, ContractSemantics.PIPELINE_VERSION,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER, PaperSource.OUR_PAPER,
                        Jurisdiction.US),
                List.of(metadata), List.of(party), List.of(definedTerm), List.of(reference),
                List.of(clause), List.of(relationship), QualityReasons.NONE,
                new Observations(3, 2, 10, 0, 2, false, false), List.of(), null);

        transactionTemplate.executeWithoutResult(status ->
                semanticsRepository.replaceSemantics(semantics));

        var loaded = semanticsRepository.find(versionId, organizationId).orElseThrow();
        assertThat(loaded.enrichmentStatus()).isEqualTo(EnrichmentStatus.READY);
        assertThat(loaded.reviewProfile().ourSideRole()).isEqualTo(PartyRole.CUSTOMER);
        assertThat(loaded.metadata()).hasSize(1);
        assertThat(loaded.metadata().get(0).value()).isEqualTo("2026-01-01");
        assertThat(loaded.parties()).hasSize(1);
        assertThat(loaded.parties().get(0).machineRoles())
                .containsExactly(PartyRole.CUSTOMER, PartyRole.PARTY_A);
        assertThat(loaded.definedTerms()).hasSize(1);
        assertThat(loaded.definedTerms().get(0).term()).isEqualTo("Fees");
        assertThat(loaded.references()).hasSize(1);
        assertThat(loaded.references().get(0).targetNodeId()).isEqualTo(targetHeadingId);
        assertThat(loaded.observations()).isNotNull();
        assertThat(loaded.observations().regionCount()).isEqualTo(3);
        assertThat(loaded.clauses()).hasSize(1);
        assertThat(loaded.clauses().get(0).primaryConcept())
                .contains(LegalConcept.LIMITATION_OF_LIABILITY);
        assertThat(loaded.clauses().get(0).facts()).hasSize(1);
        assertThat(loaded.clauses().get(0).facts().get(0).values())
                .containsEntry("liabilityCapMultiple", "3");
        assertThat(loaded.relationships()).hasSize(1);
        assertThat(loaded.relationships().get(0).type()).isEqualTo(RelationshipType.CARVES_OUT);
    }

    @Test
    void partyCorrectionPersistsWithoutErasingMachineRoles() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);
        var headingId = insertNode(versionId, null, "HEADING", "Confidentiality", "2");
        var clause = new ClauseSemantics(UUID.randomUUID(), versionId, headingId,
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE,                List.of(), "Confidential information must be protected.",
                new ConceptClassification(List.of(LegalConcept.CONFIDENTIALITY), List.of(),
                        Map.of(LegalConcept.CONFIDENTIALITY, 0.9)),
                List.of(), List.of(), FactCorrection.NONE, null);
        var party = new ContractParty(UUID.randomUUID(), versionId, "Acme Corp", "acme corp",
                List.of(), List.of(), List.of(PartyRole.PARTY_A), PartyCorrection.NONE, null);
        var semantics = new ContractSemantics(UUID.randomUUID(), versionId, organizationId,
                EnrichmentStatus.READY, ContractSemantics.PIPELINE_VERSION,
                ReviewProfile.minimal(ContractType.NDA), List.of(), List.of(party),
                List.of(), List.of(), List.of(clause), List.of(), QualityReasons.NONE, null, List.of(), null);

        transactionTemplate.executeWithoutResult(status ->
                semanticsRepository.replaceSemantics(semantics));
        var loadedParty = semanticsRepository.find(versionId, organizationId).orElseThrow()
                .parties().get(0);

        var corrected = semanticsRepository.updatePartyCorrection(
                loadedParty.id(), organizationId,
                PartyCorrection.of(List.of(PartyRole.RECEIVING_PARTY)), UUID.randomUUID());
        assertThat(corrected).isTrue();

        var reloaded = semanticsRepository.find(versionId, organizationId).orElseThrow()
                .parties().get(0);
        assertThat(reloaded.effectiveRoles()).containsExactly(PartyRole.RECEIVING_PARTY);
        assertThat(reloaded.machineRoles()).containsExactly(PartyRole.PARTY_A);
        assertThat(reloaded.roleCorrected()).isTrue();
    }

    @Test
    void statusGuardsRejectForeignVersion() {
        var organizationId = UUID.randomUUID();
        var versionId = stagedVersion(organizationId);

        transactionTemplate.executeWithoutResult(status -> semanticsRepository.replaceSemantics(
                new ContractSemantics(UUID.randomUUID(), versionId, organizationId,
                        EnrichmentStatus.READY, ContractSemantics.PIPELINE_VERSION,
                        ReviewProfile.minimal(ContractType.NDA),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        QualityReasons.NONE, null, List.of(), null)));

        assertThat(semanticsRepository.findStatus(versionId, organizationId))
                .contains(EnrichmentStatus.READY);
        assertThat(semanticsRepository.findStatus(versionId, UUID.randomUUID())).isEmpty();
        assertThat(semanticsRepository.find(versionId, UUID.randomUUID())).isEmpty();
        assertThat(semanticsRepository.updatePartyCorrection(UUID.randomUUID(),
                UUID.randomUUID(), PartyCorrection.of(List.of(PartyRole.PARTY_B)),
                UUID.randomUUID())).isFalse();
    }

    private ContractSemantics semantics(
            UUID id, UUID organizationId, UUID versionId, UUID headingId,
            EnrichmentStatus status, String pipelineVersion, String contentText) {
        var clause = new ClauseSemantics(UUID.randomUUID(), versionId, headingId,
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE,                List.of(), contentText,
                new ConceptClassification(List.of(LegalConcept.LIMITATION_OF_LIABILITY),
                        List.of(), Map.of(LegalConcept.LIMITATION_OF_LIABILITY, 0.9)),
                List.of(), List.of(), FactCorrection.NONE, null);
        return new ContractSemantics(UUID.randomUUID(), versionId, organizationId, status,
                pipelineVersion, ReviewProfile.minimal(ContractType.MSA),
                List.of(), List.of(), List.of(), List.of(), List.of(clause), List.of(),
                QualityReasons.NONE, null, List.of(), null);
    }

    private UUID stagedVersion(UUID organizationId) {
        var artifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var now = OffsetDateTime.now();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:id, :orgId, 'nda.docx', 'application/octet-stream', 42, :sha256, 'k', :createdAt)
                """)
                .param("id", artifactId).param("orgId", organizationId)
                .param("sha256", "a".repeat(64)).param("createdAt", now).update();
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, null, :createdAt)
                """)
                .param("id", contractId).param("orgId", organizationId)
                .param("createdAt", now).update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id, role,
                   parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, :organizationId, 'ORIGINAL',
                        'p', 'OK', cast('[]' as jsonb), :createdAt)
                """)
                .param("id", versionId).param("contractId", contractId)
                .param("artifactId", artifactId).param("organizationId", organizationId)
                .param("createdAt", now).update();
        return versionId;
    }

    private UUID insertNode(UUID versionId, UUID parentId, String nodeType, String text, String label) {
        var nodeId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into document_node
                  (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                   numbering_label, part_path, body_index)
                values (:id, :versionId, :parentId, :nodeType, 0, :text, :headingLevel,
                        :numberingLabel, 'word/document.xml', 0)
                """)
                .param("id", nodeId).param("versionId", versionId).param("parentId", parentId)
                .param("nodeType", nodeType).param("text", text)
                .param("headingLevel", "HEADING".equals(nodeType) ? 1 : null)
                .param("numberingLabel", label)
                .update();
        return nodeId;
    }

    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("select exists (select 1 from information_schema.tables where table_name = :name)")
                .param("name", name)
                .query(Boolean.class).single());
    }
}
