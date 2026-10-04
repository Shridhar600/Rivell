package com.rivell.workbench.platform.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ConceptClassification;
import com.rivell.workbench.contracts.ContractParty;
import com.rivell.workbench.contracts.ContractReference;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.ContractSemanticsRepository;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.FactCorrection;
import com.rivell.workbench.contracts.FactSchema;
import com.rivell.workbench.contracts.Interpretation;
import com.rivell.workbench.contracts.InterpretationKind;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.contracts.MetadataFact;
import com.rivell.workbench.contracts.MetadataField;
import com.rivell.workbench.contracts.Observations;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyCorrection;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.QualityReasons;
import com.rivell.workbench.contracts.ReferenceKind;
import com.rivell.workbench.contracts.ReferenceStatus;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.contracts.RelationshipDetector;
import com.rivell.workbench.contracts.RelationshipType;
import com.rivell.workbench.contracts.SemanticRelationship;
import com.rivell.workbench.contracts.ReviewQualityNote;
import com.rivell.workbench.contracts.DefinedTerm;
import com.rivell.workbench.contracts.VerificationState;

/**
 * JDBC implementation of the contract semantics persistence boundary. JSONB payloads
 * are written from typed records and read tolerantly (unknown enum values degrade
 * instead of poisoning the whole aggregate).
 */
@Repository
class JdbcContractSemanticsRepository implements ContractSemanticsRepository {


    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedTemplate;
    private final ObjectMapper objectMapper;

    JdbcContractSemanticsRepository(
            JdbcClient jdbcClient, NamedParameterJdbcTemplate namedTemplate,
            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.namedTemplate = namedTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public boolean beginEnrichment(
            UUID contractVersionId, UUID organizationId, ReviewProfile profile) {
        var claimed = jdbcClient.sql("""
                update contract_semantics
                set enrichment_status = 'ENRICHING', review_profile = cast(:profile as jsonb),
                    -- M10: must clear the reason. V18 couples FAILED to a non-null reason, and this
                    -- is the only statement that moves a version back to ENRICHING, so a stale reason
                    -- left here makes every retry of a FAILED enrichment violate the CHECK and
                    -- permanently brick the version.
                    enrichment_failure_reason = NULL,
                    updated_at = now()
                where contract_version_id = :versionId and organization_id = :organizationId
                  and (enrichment_status <> 'ENRICHING'
                       or updated_at < now() - interval '10 minutes')
                """)
                .param("versionId", contractVersionId)
                .param("organizationId", organizationId)
                .param("profile", toJson(reviewProfile(profile)))
                .update();
        if (claimed == 1) {
            return true;
        }
        var existing = jdbcClient.sql("""
                select id from contract_semantics
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("versionId", contractVersionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> rs.getString("id"))
                .optional();
        if (existing.isPresent()) {
            return false;
        }
        writeHeader(new com.rivell.workbench.contracts.ContractSemantics(
                UUID.randomUUID(), contractVersionId, organizationId,
                com.rivell.workbench.contracts.EnrichmentStatus.ENRICHING,
                com.rivell.workbench.contracts.ContractSemantics.PIPELINE_VERSION,
                profile, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                com.rivell.workbench.contracts.QualityReasons.NONE, null, List.of(), null));
        return true;
    }

    @Override
    public Optional<EnrichmentStatus> findStatus(UUID contractVersionId, UUID organizationId) {
        return jdbcClient.sql("""
                select enrichment_status from contract_semantics
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("versionId", contractVersionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> parseStatus(rs.getString("enrichment_status")))
                .optional();
    }

    private void writeHeader(ContractSemantics semantics) {
        var existing = jdbcClient.sql("""
                select id from contract_semantics
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("versionId", semantics.contractVersionId())
                .param("organizationId", semantics.organizationId())
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .optional();
        if (existing.isPresent()) {
            jdbcClient.sql("""
                    update contract_semantics
                    set enrichment_status = :status, pipeline_version = :pipelineVersion,
                        review_profile = cast(:profile as jsonb), quality = cast(:quality as jsonb),
                        warnings = cast(:warnings as jsonb),
                        observations = cast(:observations as jsonb),
                        enrichment_failure_reason = :failureReason, updated_at = now()
                    where contract_version_id = :versionId and organization_id = :organizationId
                    """)
                    .param("status", semantics.enrichmentStatus().name())
                    .param("pipelineVersion", semantics.pipelineVersion())
                    .param("profile", toJson(reviewProfile(semantics.reviewProfile())))
                    .param("quality", toJson(quality(semantics.quality())))
                    .param("warnings", toJson(semantics.warnings()))
                    .param("observations", toJson(observations(semantics.observations())))
                    .param("failureReason", reasonName(semantics.failureReason()))
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .update();
        } else {
            jdbcClient.sql("""
                    insert into contract_semantics
                      (id, contract_version_id, organization_id, enrichment_status,
                       pipeline_version, review_profile, quality, warnings, observations,
                       enrichment_failure_reason, created_at, updated_at)
                    values (:id, :versionId, :organizationId, :status, :pipelineVersion,
                            cast(:profile as jsonb), cast(:quality as jsonb),
                            cast(:warnings as jsonb), cast(:observations as jsonb),
                            :failureReason, :createdAt, now())
                    """)
                    .param("id", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("status", semantics.enrichmentStatus().name())
                    .param("pipelineVersion", semantics.pipelineVersion())
                    .param("profile", toJson(reviewProfile(semantics.reviewProfile())))
                    .param("quality", toJson(quality(semantics.quality())))
                    .param("warnings", toJson(semantics.warnings()))
                    .param("observations", toJson(observations(semantics.observations())))
                    .param("failureReason", reasonName(semantics.failureReason()))
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
    }

    @Override
    @Transactional
    public void replaceSemantics(ContractSemantics semantics) {
        writeHeader(semantics);
        var headerId = jdbcClient.sql("""
                select id from contract_semantics
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("versionId", semantics.contractVersionId())
                .param("organizationId", semantics.organizationId())
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .single();
        var machine = semantics.withHeaderId(headerId);

        jdbcClient.sql("delete from contract_metadata_fact where semantics_id = :id")
                .param("id", headerId).update();
        jdbcClient.sql("delete from contract_party where semantics_id = :id")
                .param("id", headerId).update();
        jdbcClient.sql("delete from contract_defined_term where semantics_id = :id")
                .param("id", headerId).update();
        jdbcClient.sql("delete from contract_reference where semantics_id = :id")
                .param("id", headerId).update();
        jdbcClient.sql("delete from contract_clause_semantics where semantics_id = :id")
                .param("id", headerId).update();
        jdbcClient.sql("delete from contract_relationship where semantics_id = :id")
                .param("id", headerId).update();

        if (!semantics.metadata().isEmpty()) {
            var rows = semantics.metadata().stream()
                    .map(fact -> new MapSqlParameterSource()
                            .addValue("id", fact.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("field", fact.field().name())
                            .addValue("value", fact.value())
                            .addValue("interpretation", toJson(interpretation(fact.interpretation())))
                            .addValue("correctedValue", fact.correctedValue())
                            .addValue("correctedBy", fact.correctedBy())
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_metadata_fact
                      (id, semantics_id, contract_version_id, organization_id, field, value,
                       interpretation, corrected_value, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :field, :value,
                            cast(:interpretation as jsonb), :correctedValue, :correctedBy, :createdAt)
                    """, rows);
        }
        if (!semantics.parties().isEmpty()) {
            var rows = semantics.parties().stream()
                    .map(party -> new MapSqlParameterSource()
                            .addValue("id", party.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("rawName", party.rawName())
                            .addValue("normalizedName", party.normalizedName())
                            .addValue("aliases", toJson(party.aliases()))
                            .addValue("interpretations",
                                    toJson(interpretations(party.interpretations())))
                            .addValue("machineRoles", toJson(names(party.machineRoles())))
                            .addValue("correctionRoles",
                                    party.correction().corrected()
                                            ? toJson(names(party.correction().roles())) : null)
                            .addValue("correctionId", party.correction().correctionId())
                            .addValue("correctedBy", party.correctedBy())
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_party
                      (id, semantics_id, contract_version_id, organization_id, raw_name,
                       normalized_name, aliases, interpretations, machine_roles,
                       correction_roles, correction_id, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :rawName,
                            :normalizedName, cast(:aliases as jsonb), cast(:interpretations as jsonb),
                            cast(:machineRoles as jsonb), cast(:correctionRoles as jsonb),
                            :correctionId, :correctedBy, :createdAt)
                    """, rows);
        }
        if (!semantics.definedTerms().isEmpty()) {
            var rows = semantics.definedTerms().stream()
                    .map(term -> new MapSqlParameterSource()
                            .addValue("id", term.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("term", term.term())
                            .addValue("definition", term.definition())
                            .addValue("definingNodeId", term.definingNodeId())
                            .addValue("sourceNodeIds", toJson(uuids(term.sourceNodeIds())))
                            .addValue("referenceNodeIds", toJson(uuids(term.referenceNodeIds())))
                            .addValue("duplicate", term.duplicate())
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_defined_term
                      (id, semantics_id, contract_version_id, organization_id, term, definition,
                       defining_node_id, source_node_ids, reference_node_ids, duplicate, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :term, :definition,
                            :definingNodeId, cast(:sourceNodeIds as jsonb),
                            cast(:referenceNodeIds as jsonb), :duplicate, :createdAt)
                    """, rows);
        }
        if (!semantics.references().isEmpty()) {
            var rows = semantics.references().stream()
                    .map(reference -> new MapSqlParameterSource()
                            .addValue("id", reference.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("sourceNodeId", reference.sourceNodeId())
                            .addValue("referenceText", reference.referenceText())
                            .addValue("kind", reference.kind().name())
                            .addValue("status", reference.status().name())
                            .addValue("targetNodeId", reference.targetNodeId())
                            .addValue("candidates", toJson(uuids(reference.candidateNodeIds())))
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_reference
                      (id, semantics_id, contract_version_id, organization_id, source_node_id,
                       reference_text, kind, status, target_node_id, candidate_node_ids, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :sourceNodeId,
                            :referenceText, :kind, :status, :targetNodeId,
                            cast(:candidates as jsonb), :createdAt)
                    """, rows);
        }
        if (!semantics.clauses().isEmpty()) {
            var rows = semantics.clauses().stream()
                    .map(clause -> new MapSqlParameterSource()
                            .addValue("id", clause.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("headingNodeId", clause.headingNodeId())
                            .addValue("regionKind", clause.regionKind().name())
                            .addValue("textNodeIds", toJson(uuids(clause.textNodeIds())))
                            .addValue("contentText", clause.contentText())
                            .addValue("classification", toJson(classification(clause.classification())))
                            .addValue("facts", toJson(facts(clause.facts())))
                            .addValue("interpretations",
                                    toJson(interpretations(clause.interpretations())))
                            .addValue("correction",
                                    clause.correction().corrected()
                                            ? toJson(factCorrection(clause.correction())) : null)
                            .addValue("correctedBy", clause.correctedBy())
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_clause_semantics
                      (id, semantics_id, contract_version_id, organization_id, heading_node_id,
                       region_kind, text_node_ids, content_text, classification, facts,
                       interpretations, correction, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :headingNodeId,
                            :regionKind, cast(:textNodeIds as jsonb), :contentText,
                            cast(:classification as jsonb), cast(:facts as jsonb),
                            cast(:interpretations as jsonb), cast(:correction as jsonb),
                            :correctedBy, :createdAt)
                    """, rows);
        }
        if (!semantics.relationships().isEmpty()) {
            var rows = semantics.relationships().stream()
                    .map(relationship -> new MapSqlParameterSource()
                            .addValue("id", relationship.id())
                            .addValue("semanticsId", headerId)
                            .addValue("versionId", machine.contractVersionId())
                            .addValue("organizationId", machine.organizationId())
                            .addValue("sourceRegionId", relationship.sourceRegionNodeId())
                            .addValue("targetRegionId", relationship.targetRegionNodeId())
                            .addValue("type", relationship.type().name())
                            .addValue("detector", relationship.detector().name())
                            .addValue("basis", relationship.basis())
                            .addValue("probability", relationship.probability())
                            .addValue("executionId", relationship.executionId())
                            .addValue("verification", relationship.verification().name())
                            .addValue("verificationExecutionId",
                                    relationship.verificationExecutionId())
                            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)))
                    .toArray(MapSqlParameterSource[]::new);
            namedTemplate.batchUpdate("""
                    insert into contract_relationship
                      (id, semantics_id, contract_version_id, organization_id,
                       source_region_node_id, target_region_node_id, type, detector, basis,
                       probability, execution_id, verification, verification_execution_id, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :sourceRegionId,
                            :targetRegionId, :type, :detector, :basis, :probability, :executionId,
                            :verification, :verificationExecutionId, :createdAt)
                    """, rows);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractSemantics> find(UUID contractVersionId, UUID organizationId) {
        var header = jdbcClient.sql("""
                select id, enrichment_status, pipeline_version, review_profile, quality,
                       observations, warnings, enrichment_failure_reason
                from contract_semantics
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("versionId", contractVersionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new SemanticsHeader(
                        UUID.fromString(rs.getString("id")),
                        parseStatus(rs.getString("enrichment_status")),
                        rs.getString("pipeline_version"),
                        parseProfile(rs.getString("review_profile")),
                        parseQuality(rs.getString("quality")),
                        parseObservations(rs.getString("observations")),
                        readStrings(rs.getString("warnings")),
                        parseReason(rs.getString("enrichment_failure_reason"))))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        var semanticsId = header.get().id();
        var versionId = contractVersionId;
        var knownNodeIds = jdbcClient.sql(
                        "select id from document_node where contract_version_id = :versionId")
                .param("versionId", versionId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list()
                .stream()
                .collect(Collectors.toSet());

        var metadataRows = jdbcClient.sql("""
                        select id, field, value, interpretation, corrected_value, corrected_by
                        from contract_metadata_fact where semantics_id = :id
                        order by created_at
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new MetadataRow(
                        UUID.fromString(rs.getString("id")),
                        parseEnum(MetadataField.class, rs.getString("field")),
                        rs.getString("value"),
                        parseInterpretation(rs.getString("interpretation")),
                        rs.getString("corrected_value"),
                        optionalUuid(rs, "corrected_by")))
                .list();
        var metadata = metadataRows.stream()
                .filter(row -> row.field() != null && row.interpretation() != null)
                .map(row -> new MetadataFact(row.id(), versionId, row.field(), row.value(),
                        row.interpretation(), row.correctedValue(), row.correctedBy()))
                .toList();

        var parties = jdbcClient.sql("""
                        select id, raw_name, normalized_name, aliases, interpretations,
                               machine_roles, correction_roles, correction_id, corrected_by
                        from contract_party where semantics_id = :id
                        order by created_at
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new ContractParty(
                        UUID.fromString(rs.getString("id")), versionId,
                        rs.getString("raw_name"), rs.getString("normalized_name"),
                        readStrings(rs.getString("aliases")),
                        parseInterpretations(rs.getString("interpretations")),
                        parseRoles(rs.getString("machine_roles")),
                        readPartyCorrection(rs.getString("correction_roles"),
                                rs.getString("correction_id")),
                        optionalUuid(rs, "corrected_by")))
                .list();

        var definedTerms = jdbcClient.sql("""
                        select id, term, definition, defining_node_id, source_node_ids,
                               reference_node_ids, duplicate
                        from contract_defined_term where semantics_id = :id
                        order by created_at
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new DefinedTerm(
                        UUID.fromString(rs.getString("id")), versionId,
                        rs.getString("term"), rs.getString("definition"),
                        UUID.fromString(rs.getString("defining_node_id")),
                        readUuids(rs.getString("source_node_ids")),
                        filterKnown(readUuids(rs.getString("reference_node_ids")), knownNodeIds),
                        rs.getBoolean("duplicate")))
                .list().stream()
                .filter(term -> knownNodeIds.contains(term.definingNodeId()))
                .toList();

        var references = jdbcClient.sql("""
                        select id, source_node_id, reference_text, kind, status, target_node_id,
                               candidate_node_ids
                        from contract_reference where semantics_id = :id
                        order by created_at
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new ContractReference(
                        UUID.fromString(rs.getString("id")), versionId,
                        UUID.fromString(rs.getString("source_node_id")),
                        rs.getString("reference_text"),
                        parseEnum(ReferenceKind.class, rs.getString("kind")),
                        parseEnum(ReferenceStatus.class, rs.getString("status")),
                        optionalUuid(rs, "target_node_id"),
                        filterKnown(readUuids(rs.getString("candidate_node_ids")), knownNodeIds)))
                .list().stream()
                .filter(reference -> knownNodeIds.contains(reference.sourceNodeId()))
                .filter(reference -> reference.kind() != null && reference.status() != null)
                .toList();

        // Clauses are anchored to the heading node they were extracted under, so their meaningful order
        // is the order those headings appear in the document. Ordering by the same recursive
        // sort_path the document read uses keeps a contract's clauses in reading order on every
        // read, instead of whatever order the storage engine happens to return them in.
        // SemanticsDigest sorts clauses itself, so the review snapshot pin is unaffected either
        // way. The heading-node id is only a tie-break for a clause whose node is missing, where
        // no document position exists; the left join keeps such a clause readable rather than
        // dropping it.
        var clauses = jdbcClient.sql("""
                        with recursive tree as (
                            select n.id, array[n.ordinal] as sort_path
                            from document_node n
                            where n.contract_version_id = :versionId and n.parent_id is null
                            union all
                            select n.id, t.sort_path || n.ordinal
                            from document_node n
                            join tree t on n.parent_id = t.id
                            where n.contract_version_id = :versionId
                        )
                        select c.id, c.heading_node_id, c.region_kind, c.text_node_ids,
                               c.content_text, c.classification, c.facts, c.interpretations,
                               c.correction, c.corrected_by
                        from contract_clause_semantics c
                        left join tree on tree.id = c.heading_node_id
                        where c.semantics_id = :id
                        order by tree.sort_path nulls last, c.heading_node_id
                        """)
                .param("id", semanticsId)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new ClauseSemantics(
                        UUID.fromString(rs.getString("id")), versionId,
                        UUID.fromString(rs.getString("heading_node_id")),
                        parseEnum(ContractRegionSegmenter.SemanticRegionKind.class,
                                rs.getString("region_kind")),
                        filterKnown(readUuids(rs.getString("text_node_ids")), knownNodeIds),
                        rs.getString("content_text"),
                        parseClassification(rs.getString("classification")),
                        parseFacts(rs.getString("facts")),
                        validatedInterpretations(rs.getString("interpretations"), knownNodeIds),
                        parseFactCorrection(rs.getString("correction")),
                        optionalUuid(rs, "corrected_by")))
                .list().stream()
                .filter(clause -> knownNodeIds.contains(clause.headingNodeId()))
                .filter(clause -> clause.regionKind() != null)
                .toList();

        var relationshipRows = jdbcClient.sql("""
                        select id, source_region_node_id, target_region_node_id, type, detector,
                               basis, probability, execution_id, verification,
                               verification_execution_id
                        from contract_relationship where semantics_id = :id
                        order by created_at
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new RelationshipRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("source_region_node_id")),
                        UUID.fromString(rs.getString("target_region_node_id")),
                        parseEnum(RelationshipType.class, rs.getString("type")),
                        parseEnum(RelationshipDetector.class, rs.getString("detector")),
                        rs.getString("basis"), rs.getDouble("probability"),
                        optionalUuid(rs, "execution_id"),
                        parseEnum(VerificationState.class, rs.getString("verification")),
                        optionalUuid(rs, "verification_execution_id")))
                .list();
        var relationships = relationshipRows.stream()
                .filter(row -> row.type() != null && row.detector() != null
                        && row.verification() != null)
                .filter(row -> knownNodeIds.contains(row.sourceRegionNodeId())
                        && knownNodeIds.contains(row.targetRegionNodeId()))
                .map(row -> new SemanticRelationship(row.id(), versionId,
                        row.sourceRegionNodeId(), row.targetRegionNodeId(), row.type(),
                        row.detector(), row.basis(), row.probability(), row.executionId(),
                        row.verification(), row.verificationExecutionId()))
                .toList();

        var headerValue = header.get();
        return Optional.of(new ContractSemantics(semanticsId, versionId, organizationId,
                headerValue.status(), headerValue.pipelineVersion(), headerValue.profile(),
                metadata, parties, definedTerms, references, clauses, relationships,
                headerValue.quality(), headerValue.observations(), headerValue.warnings(),
                headerValue.failureReason()));
    }

    @Override
    @Transactional
    public boolean updatePartyCorrection(
            UUID partyId, UUID organizationId, PartyCorrection correction, UUID correctedBy) {
        return jdbcClient.sql("""
                update contract_party
                set correction_roles = cast(:roles as jsonb), correction_id = :correctionId,
                    corrected_by = :correctedBy
                where id = :partyId and organization_id = :organizationId
                """)
                .param("partyId", partyId)
                .param("organizationId", organizationId)
                .param("roles",
                        correction.corrected() ? toJson(names(correction.roles())) : null)
                .param("correctionId", correction.correctionId())
                .param("correctedBy", correctedBy)
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean updateClauseCorrection(
            UUID clauseId, UUID organizationId, FactCorrection correction, UUID correctedBy) {
        return jdbcClient.sql("""
                update contract_clause_semantics
                set correction = cast(:correction as jsonb), corrected_by = :correctedBy
                where id = :clauseId and organization_id = :organizationId
                """)
                .param("clauseId", clauseId)
                .param("organizationId", organizationId)
                .param("correction", toJson(factCorrection(correction)))
                .param("correctedBy", correctedBy)
                .update() == 1;
    }

    private static String reasonName(
            com.rivell.workbench.contracts.EnrichmentFailureReason reason) {
        return reason == null ? null : reason.name();
    }

    private static com.rivell.workbench.contracts.EnrichmentFailureReason parseReason(
            String value) {
        return value == null ? null
                : com.rivell.workbench.contracts.EnrichmentFailureReason.valueOf(value);
    }

    private record SemanticsHeader(
            UUID id, EnrichmentStatus status, String pipelineVersion,
            ReviewProfile profile, QualityReasons quality, Observations observations,
            List<String> warnings,
            com.rivell.workbench.contracts.EnrichmentFailureReason failureReason) {
    }

    private record MetadataRow(
            UUID id, MetadataField field, String value, Interpretation interpretation,
            String correctedValue, UUID correctedBy) {
    }

    private record RelationshipRow(
            UUID id, UUID sourceRegionNodeId, UUID targetRegionNodeId, RelationshipType type,
            RelationshipDetector detector, String basis, double probability, UUID executionId,
            VerificationState verification, UUID verificationExecutionId) {
    }

    private Map<String, Object> reviewProfile(ReviewProfile profile) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contractType", profile.contractType().name());
        if (profile.ourSideRole() != null) {
            payload.put("ourSideRole", profile.ourSideRole().name());
        }
        if (profile.paperSource() != null) {
            payload.put("paperSource", profile.paperSource().name());
        }
        payload.put("jurisdiction",
                profile.jurisdiction() == null ? Jurisdiction.UNSPECIFIED.name()
                        : profile.jurisdiction().name());
        if (profile.contractPackKey() != null) {
            payload.put("contractPackKey", profile.contractPackKey());
        }
        return payload;
    }

    private ReviewProfile parseProfile(String json) {
        var node = readTree(json);
        var contractType = parseEnum(ContractType.class, text(node, "contractType"));
        if (contractType == null) {
            contractType = ContractType.OTHER;
        }
        var jurisdiction = parseEnum(Jurisdiction.class, text(node, "jurisdiction"));
        return new ReviewProfile(contractType,
                parseEnum(com.rivell.workbench.contracts.PartyRole.class, text(node, "ourSideRole")),
                parseEnum(PaperSource.class, text(node, "paperSource")),
                jurisdiction == null ? Jurisdiction.UNSPECIFIED : jurisdiction,
                text(node, "contractPackKey"));
    }

    private List<String> quality(QualityReasons quality) {
        return quality.notes().stream().map(Enum::name).toList();
    }

    private Map<String, Object> observations(Observations observations) {
        if (observations == null) {
            return null;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("regionCount", observations.regionCount());
        payload.put("decisionExecutionCount", observations.decisionExecutionCount());
        payload.put("questionCount", observations.questionCount());
        payload.put("generativeExtractionCount", observations.generativeExtractionCount());
        payload.put("deterministicFactCount", observations.deterministicFactCount());
        payload.put("providerDegraded", observations.providerDegraded());
        payload.put("budgetExhausted", observations.budgetExhausted());
        return payload;
    }

    private Observations parseObservations(String json) {
        var node = readTree(json);
        if (node == null || !node.hasNonNull("regionCount")) {
            return null;
        }
        return new Observations(
                node.get("regionCount").asInt(),
                node.path("decisionExecutionCount").asInt(0),
                node.path("questionCount").asInt(0),
                node.path("generativeExtractionCount").asInt(0),
                node.path("deterministicFactCount").asInt(0),
                node.path("providerDegraded").asBoolean(false),
                node.path("budgetExhausted").asBoolean(false));
    }

    private QualityReasons parseQuality(String json) {
        if (json == null) {
            return QualityReasons.NONE;
        }
        try {
            var notes = new ArrayList<ReviewQualityNote>();
            for (var element : objectMapper.readTree(json)) {
                var note = parseEnum(ReviewQualityNote.class, element.asText());
                if (note != null && note != ReviewQualityNote.NONE) {
                    notes.add(note);
                }
            }
            return QualityReasons.of(notes);
        } catch (Exception e) {
            return QualityReasons.NONE;
        }
    }

    private List<Map<String, Object>> interpretations(List<Interpretation> interpretations) {
        var payload = new ArrayList<Map<String, Object>>();
        for (var interpretation : interpretations) {
            payload.add(interpretation(interpretation));
        }
        return payload;
    }

    private Map<String, Object> interpretation(Interpretation interpretation) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("field", interpretation.field());
        payload.put("kind", interpretation.kind().name());
        payload.put("proposedValue", interpretation.proposedValue());
        payload.put("specId", interpretation.specId());
        payload.put("specVersion", interpretation.specVersion());
        if (interpretation.executionId() != null) {
            payload.put("executionId", interpretation.executionId().toString());
        }
        payload.put("sourceNodeIds", uuids(interpretation.sourceNodeIds()));
        payload.put("verification", interpretation.verification().name());
        if (interpretation.verificationExecutionId() != null) {
            payload.put("verificationExecutionId",
                    interpretation.verificationExecutionId().toString());
        }
        return payload;
    }

    private List<Interpretation> validatedInterpretations(String json, Set<UUID> knownNodes) {
        return parseInterpretations(json).stream()
                .map(interpretation -> new Interpretation(
                        interpretation.field(), interpretation.kind(),
                        interpretation.proposedValue(), interpretation.specId(),
                        interpretation.specVersion(), interpretation.executionId(),
                        filterKnown(interpretation.sourceNodeIds(), knownNodes),
                        interpretation.verification(), interpretation.verificationExecutionId()))
                .toList();
    }

    private List<Interpretation> parseInterpretations(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var interpretations = new ArrayList<Interpretation>();
            for (var element : objectMapper.readTree(json)) {
                var interpretation = parseInterpretation(element);
                if (interpretation != null) {
                    interpretations.add(interpretation);
                }
            }
            return interpretations;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Interpretation parseInterpretation(String json) {
        return parseInterpretation(readTree(json));
    }

    private Interpretation parseInterpretation(JsonNode node) {
        if (node == null || !node.hasNonNull("field") || !node.hasNonNull("proposedValue")
                || !node.hasNonNull("specId") || !node.hasNonNull("specVersion")
                || !node.hasNonNull("verification")) {
            return null;
        }
        var kind = parseEnum(InterpretationKind.class, text(node, "kind"));
        if (kind == null) {
            kind = InterpretationKind.DETERMINISTIC;
        }
        var verification = parseEnum(VerificationState.class, text(node, "verification"));
        if (verification == null) {
            verification = VerificationState.NOT_VERIFIED;
        }
        return new Interpretation(
                node.get("field").asText(),
                kind,
                node.get("proposedValue").asText(),
                node.get("specId").asText(),
                node.get("specVersion").asInt(),
                optionalUuid(node.get("executionId")),
                readUuids(node.get("sourceNodeIds")),
                verification,
                optionalUuid(node.get("verificationExecutionId")));
    }

    private Map<String, Object> classification(ConceptClassification classification) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("concepts", names(classification.concepts()));
        payload.put("secondaryConcepts", names(classification.secondaryConcepts()));
        var probabilities = new LinkedHashMap<String, Object>();
        for (var entry : classification.probabilities().entrySet()) {
            probabilities.put(entry.getKey().name(), entry.getValue());
        }
        payload.put("probabilities", probabilities);
        return payload;
    }

    private ConceptClassification parseClassification(String json) {
        var node = readTree(json);
        if (node == null) {
            return new ConceptClassification(List.of(), List.of(), Map.of());
        }
        var concepts = new ArrayList<LegalConcept>();
        for (var element : node.path("concepts")) {
            var concept = parseEnum(LegalConcept.class, element.asText());
            if (concept != null) {
                concepts.add(concept);
            }
        }
        var secondaries = new ArrayList<LegalConcept>();
        for (var element : node.path("secondaryConcepts")) {
            var concept = parseEnum(LegalConcept.class, element.asText());
            if (concept != null) {
                secondaries.add(concept);
            }
        }
        var probabilities = new LinkedHashMap<LegalConcept, Double>();
        var probabilityNode = node.path("probabilities");
        if (probabilityNode.isObject()) {
            probabilityNode.fields().forEachRemaining(entry -> {
                var concept = parseEnum(LegalConcept.class, entry.getKey());
                if (concept != null && entry.getValue().isNumber()) {
                    probabilities.put(concept, entry.getValue().asDouble());
                }
            });
        }
        return new ConceptClassification(concepts, secondaries, probabilities);
    }

    private List<Map<String, Object>> facts(List<LegalFact> facts) {
        var payload = new ArrayList<Map<String, Object>>();
        for (var fact : facts) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("factSchema", fact.factSchema());
            entry.put("schemaVersion", fact.schemaVersion());
            entry.put("values", new LinkedHashMap<String, Object>(fact.values()));
            payload.add(entry);
        }
        return payload;
    }

    private List<LegalFact> parseFacts(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var facts = new ArrayList<LegalFact>();
            for (var element : objectMapper.readTree(json)) {
                if (!element.hasNonNull("factSchema") || !element.hasNonNull("values")) {
                    continue;
                }
                try {
                    var schema = FactSchema.byId(element.get("factSchema").asText());
                    var values = new LinkedHashMap<String, String>();
                    element.path("values").fields().forEachRemaining(entry -> {
                        if (schema.fields().contains(entry.getKey())
                                && entry.getValue().isTextual()) {
                            values.put(entry.getKey(), entry.getValue().asText());
                        }
                    });
                    if (!values.isEmpty()) {
                        facts.add(new LegalFact(schema.id(), schema.version(), values));
                    }
                } catch (IllegalArgumentException ignoredSchemaOrValue) {
                    // unsupported or malformed fact shape degrades, never poisons the read
                }
            }
            return facts;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> factCorrection(FactCorrection correction) {
        var payload = new LinkedHashMap<String, Object>();
        if (correction.primaryConcept() != null) {
            payload.put("primaryConcept", correction.primaryConcept());
        }
        if (correction.factValues() != null && !correction.factValues().isEmpty()) {
            payload.put("factValues",
                    new LinkedHashMap<String, Object>(correction.factValues()));
        }
        if (correction.correctionId() != null) {
            payload.put("correctionId", correction.correctionId().toString());
        }
        return payload;
    }

    private FactCorrection parseFactCorrection(String json) {
        if (json == null) {
            return FactCorrection.NONE;
        }
        var node = readTree(json);
        if (node == null) {
            return FactCorrection.NONE;
        }
        var primary = text(node, "primaryConcept");
        var factValues = new LinkedHashMap<String, String>();
        node.path("factValues").fields().forEachRemaining(
                entry -> factValues.put(entry.getKey(), entry.getValue().asText()));
        if (primary == null && factValues.isEmpty()) {
            return FactCorrection.NONE;
        }
        return new FactCorrection(primary, factValues, optionalUuid(node.get("correctionId")));
    }

    private PartyCorrection readPartyCorrection(String rolesJson, String correctionId) {
        if (rolesJson == null || correctionId == null) {
            return PartyCorrection.NONE;
        }
        var roles = parseRoles(rolesJson);
        if (roles.isEmpty()) {
            return PartyCorrection.NONE;
        }
        return new PartyCorrection(roles, UUID.fromString(correctionId));
    }

    private List<PartyRole> parseRoles(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var roles = new ArrayList<com.rivell.workbench.contracts.PartyRole>();
            for (var element : objectMapper.readTree(json)) {
                var role = parseEnum(com.rivell.workbench.contracts.PartyRole.class,
                        element.asText());
                if (role != null) {
                    roles.add(role);
                }
            }
            return roles;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> names(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    private List<String> uuids(List<UUID> values) {
        return values.stream().map(UUID::toString).toList();
    }

    private List<UUID> readUuids(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var ids = new ArrayList<UUID>();
            for (var element : objectMapper.readTree(json)) {
                try {
                    ids.add(UUID.fromString(element.asText()));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed id; integrity validated at load
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<UUID> readUuids(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var ids = new ArrayList<UUID>();
        for (var element : node) {
            try {
                ids.add(UUID.fromString(element.asText()));
            } catch (IllegalArgumentException ignored) {
                // skip malformed id
            }
        }
        return ids;
    }

    private List<String> readStrings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var values = new ArrayList<String>();
            for (var element : objectMapper.readTree(json)) {
                values.add(element.asText());
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<UUID> filterKnown(List<UUID> ids, Set<UUID> known) {
        return ids.stream().filter(known::contains).toList();
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private UUID optionalUuid(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID optionalUuid(java.sql.ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private EnrichmentStatus parseStatus(String value) {
        var status = parseEnum(EnrichmentStatus.class, value);
        return status == null ? EnrichmentStatus.FAILED : status;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Contract semantics payload could not be serialized", e);
        }
    }
}
