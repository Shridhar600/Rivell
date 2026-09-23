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
    private final ObjectMapper objectMapper;

    JdbcContractSemanticsRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void insertSemanticsRow(ContractSemantics semantics) {
        jdbcClient.sql("""
                insert into contract_semantics
                  (id, contract_version_id, organization_id, enrichment_status, pipeline_version,
                   review_profile, quality, warnings, interpretations, created_at)
                values (:id, :versionId, :organizationId, :status, :pipelineVersion,
                        cast(:profile as jsonb), cast(:quality as jsonb), cast(:warnings as jsonb),
                        cast(:interpretations as jsonb), :createdAt)
                """)
                .param("id", semantics.id())
                .param("versionId", semantics.contractVersionId())
                .param("organizationId", semantics.organizationId())
                .param("status", semantics.enrichmentStatus().name())
                .param("pipelineVersion", semantics.pipelineVersion())
                .param("profile", toJson(reviewProfile(semantics.reviewProfile())))
                .param("quality", toJson(quality(semantics.quality())))
                .param("warnings", toJson(List.of()))
                .param("interpretations", toJson(interpretations(semantics.interpretations())))
                .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional
    public boolean updateStatus(UUID contractVersionId, UUID organizationId, EnrichmentStatus status) {
        return jdbcClient.sql("""
                update contract_semantics
                set enrichment_status = :status
                where contract_version_id = :contractVersionId and organization_id = :organizationId
                """)
                .param("contractVersionId", contractVersionId)
                .param("organizationId", organizationId)
                .param("status", status.name())
                .update() == 1;
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

    @Override
    @Transactional
    public void replaceSemantics(ContractSemantics semantics) {
        jdbcClient.sql("""
                insert into contract_semantics
                  (id, contract_version_id, organization_id, enrichment_status, pipeline_version,
                   review_profile, quality, warnings, interpretations, created_at)
                values (:id, :versionId, :organizationId, :status, :pipelineVersion,
                        cast(:profile as jsonb), cast(:quality as jsonb), cast(:warnings as jsonb),
                        cast(:interpretations as jsonb), :createdAt)
                on conflict (contract_version_id) do update set
                  enrichment_status = excluded.enrichment_status,
                  pipeline_version = excluded.pipeline_version,
                  review_profile = excluded.review_profile,
                  quality = excluded.quality,
                  warnings = excluded.warnings,
                  interpretations = excluded.interpretations
                """)
                .param("id", semantics.id())
                .param("versionId", semantics.contractVersionId())
                .param("organizationId", semantics.organizationId())
                .param("status", semantics.enrichmentStatus().name())
                .param("pipelineVersion", semantics.pipelineVersion())
                .param("profile", toJson(reviewProfile(semantics.reviewProfile())))
                .param("quality", toJson(quality(semantics.quality())))
                .param("warnings", toJson(List.of()))
                .param("interpretations", toJson(interpretations(semantics.interpretations())))
                .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();

        jdbcClient.sql("delete from contract_metadata_fact where semantics_id = :id")
                .param("id", semantics.id()).update();
        jdbcClient.sql("delete from contract_party where semantics_id = :id")
                .param("id", semantics.id()).update();
        jdbcClient.sql("delete from contract_defined_term where semantics_id = :id")
                .param("id", semantics.id()).update();
        jdbcClient.sql("delete from contract_reference where semantics_id = :id")
                .param("id", semantics.id()).update();
        jdbcClient.sql("delete from contract_clause_semantics where semantics_id = :id")
                .param("id", semantics.id()).update();
        jdbcClient.sql("delete from contract_relationship where semantics_id = :id")
                .param("id", semantics.id()).update();

        for (var fact : semantics.metadata()) {
            jdbcClient.sql("""
                    insert into contract_metadata_fact
                      (id, semantics_id, contract_version_id, organization_id, field, value,
                       interpretation, corrected_value, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :field, :value,
                            cast(:interpretation as jsonb), :correctedValue, :correctedBy, :createdAt)
                    """)
                    .param("id", fact.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("field", fact.field().name())
                    .param("value", fact.value())
                    .param("interpretation", toJson(interpretation(fact.interpretation())))
                    .param("correctedValue", fact.correctedValue())
                    .param("correctedBy", fact.correctedBy())
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        for (var party : semantics.parties()) {
            jdbcClient.sql("""
                    insert into contract_party
                      (id, semantics_id, contract_version_id, organization_id, raw_name,
                       normalized_name, aliases, interpretations, machine_roles,
                       correction_roles, correction_id, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :rawName,
                            :normalizedName, cast(:aliases as jsonb), cast(:interpretations as jsonb),
                            cast(:machineRoles as jsonb), cast(:correctionRoles as jsonb),
                            :correctionId, :correctedBy, :createdAt)
                    """)
                    .param("id", party.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("rawName", party.rawName())
                    .param("normalizedName", party.normalizedName())
                    .param("aliases", toJson(party.aliases()))
                    .param("interpretations", toJson(interpretations(party.interpretations())))
                    .param("machineRoles", toJson(names(party.machineRoles())))
                    .param("correctionRoles",
                            party.correction().corrected()
                                    ? toJson(names(party.correction().roles())) : null)
                    .param("correctionId", party.correction().correctionId())
                    .param("correctedBy", party.correctedBy())
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        for (var term : semantics.definedTerms()) {
            jdbcClient.sql("""
                    insert into contract_defined_term
                      (id, semantics_id, contract_version_id, organization_id, term, definition,
                       defining_node_id, source_node_ids, reference_node_ids, duplicate, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :term, :definition,
                            :definingNodeId, cast(:sourceNodeIds as jsonb),
                            cast(:referenceNodeIds as jsonb), :duplicate, :createdAt)
                    """)
                    .param("id", term.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("term", term.term())
                    .param("definition", term.definition())
                    .param("definingNodeId", term.definingNodeId())
                    .param("sourceNodeIds", toJson(uuids(term.sourceNodeIds())))
                    .param("referenceNodeIds", toJson(uuids(term.referenceNodeIds())))
                    .param("duplicate", term.duplicate())
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        for (var reference : semantics.references()) {
            jdbcClient.sql("""
                    insert into contract_reference
                      (id, semantics_id, contract_version_id, organization_id, source_node_id,
                       reference_text, kind, status, target_node_id, candidate_node_ids, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :sourceNodeId,
                            :referenceText, :kind, :status, :targetNodeId,
                            cast(:candidates as jsonb), :createdAt)
                    """)
                    .param("id", reference.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("sourceNodeId", reference.sourceNodeId())
                    .param("referenceText", reference.referenceText())
                    .param("kind", reference.kind().name())
                    .param("status", reference.status().name())
                    .param("targetNodeId", reference.targetNodeId())
                    .param("candidates", toJson(uuids(reference.candidateNodeIds())))
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        for (var clause : semantics.clauses()) {
            jdbcClient.sql("""
                    insert into contract_clause_semantics
                      (id, semantics_id, contract_version_id, organization_id, heading_node_id,
                       text_node_ids, content_text, classification, facts, interpretations,
                       correction, corrected_by, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :headingNodeId,
                            cast(:textNodeIds as jsonb), :contentText, cast(:classification as jsonb),
                            cast(:facts as jsonb), cast(:interpretations as jsonb),
                            cast(:correction as jsonb), :correctedBy, :createdAt)
                    """)
                    .param("id", clause.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("headingNodeId", clause.headingNodeId())
                    .param("textNodeIds", toJson(uuids(clause.textNodeIds())))
                    .param("contentText", clause.contentText())
                    .param("classification", toJson(classification(clause.classification())))
                    .param("facts", toJson(facts(clause.facts())))
                    .param("interpretations", toJson(interpretations(clause.interpretations())))
                    .param("correction",
                            clause.correction().corrected()
                                    ? toJson(factCorrection(clause.correction())) : null)
                    .param("correctedBy", clause.correctedBy())
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        for (var relationship : semantics.relationships()) {
            jdbcClient.sql("""
                    insert into contract_relationship
                      (id, semantics_id, contract_version_id, organization_id,
                       source_region_node_id, target_region_node_id, type, detector, basis,
                       probability, execution_id, verification, verification_execution_id, created_at)
                    values (:id, :semanticsId, :versionId, :organizationId, :sourceRegionId,
                            :targetRegionId, :type, :detector, :basis, :probability, :executionId,
                            :verification, :verificationExecutionId, :createdAt)
                    """)
                    .param("id", relationship.id())
                    .param("semanticsId", semantics.id())
                    .param("versionId", semantics.contractVersionId())
                    .param("organizationId", semantics.organizationId())
                    .param("sourceRegionId", relationship.sourceRegionNodeId())
                    .param("targetRegionId", relationship.targetRegionNodeId())
                    .param("type", relationship.type().name())
                    .param("detector", relationship.detector().name())
                    .param("basis", relationship.basis())
                    .param("probability", relationship.probability())
                    .param("executionId", relationship.executionId())
                    .param("verification", relationship.verification().name())
                    .param("verificationExecutionId", relationship.verificationExecutionId())
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractSemantics> find(UUID contractVersionId, UUID organizationId) {
        var header = jdbcClient.sql("""
                select id, enrichment_status, pipeline_version, review_profile, quality,
                       interpretations
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
                        parseInterpretations(rs.getString("interpretations"))))
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

        var clauses = jdbcClient.sql("""
                        select id, heading_node_id, text_node_ids, content_text, classification,
                               facts, interpretations, correction, corrected_by
                        from contract_clause_semantics where semantics_id = :id
                        """)
                .param("id", semanticsId)
                .query((rs, rowNum) -> new ClauseSemantics(
                        UUID.fromString(rs.getString("id")), versionId,
                        UUID.fromString(rs.getString("heading_node_id")),
                        filterKnown(readUuids(rs.getString("text_node_ids")), knownNodeIds),
                        rs.getString("content_text"),
                        parseClassification(rs.getString("classification")),
                        parseFacts(rs.getString("facts")),
                        parseInterpretations(rs.getString("interpretations")),
                        parseFactCorrection(rs.getString("correction")),
                        optionalUuid(rs, "corrected_by")))
                .list().stream()
                .filter(clause -> knownNodeIds.contains(clause.headingNodeId()))
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
                headerValue.quality(), headerValue.interpretations()));
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

    private record SemanticsHeader(
            UUID id, EnrichmentStatus status, String pipelineVersion,
            ReviewProfile profile, QualityReasons quality,
            List<Interpretation> interpretations) {
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
                jurisdiction == null ? Jurisdiction.UNSPECIFIED : jurisdiction);
    }

    private List<String> quality(QualityReasons quality) {
        return quality.notes().stream().map(Enum::name).toList();
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
        return node == null || node.isNull() || node.asText().isBlank()
                ? null : UUID.fromString(node.asText());
    }

    private static UUID optionalUuid(java.sql.ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
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
