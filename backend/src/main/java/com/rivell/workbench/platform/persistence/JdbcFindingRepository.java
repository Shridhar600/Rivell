package com.rivell.workbench.platform.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.review.ClaimKind;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingClaim;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;

@Repository
class JdbcFindingRepository implements FindingRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcFindingRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void replaceFindings(
            UUID organizationId, UUID reviewId, UUID runId, List<Finding> findings) {
        deleteRunFindings(organizationId, runId);
        for (var finding : findings) {
            insertFinding(finding);
            insertChildren(finding);
        }
    }

    @Override
    @Transactional
    public void deleteFindings(UUID organizationId, UUID runId) {
        deleteRunFindings(organizationId, runId);
    }

    private void deleteRunFindings(UUID organizationId, UUID runId) {
        var findingIds = jdbcClient.sql("""
                select id from finding
                where run_id = :runId and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query(UUID.class)
                .list();
        for (var findingId : findingIds) {
            jdbcClient.sql("delete from finding_claim where finding_id = :id")
                    .param("id", findingId).update();
            jdbcClient.sql("delete from finding_concept where finding_id = :id")
                    .param("id", findingId).update();
            jdbcClient.sql("delete from finding_evidence where finding_id = :id")
                    .param("id", findingId).update();
            jdbcClient.sql("delete from finding_signal where finding_id = :id")
                    .param("id", findingId).update();
        }
        jdbcClient.sql("""
                delete from finding
                where run_id = :runId and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .update();
    }

    private void insertFinding(Finding finding) {
        jdbcClient.sql("""
                insert into finding
                  (id, organization_id, review_id, run_id, consolidation_key, subject_kind,
                   subject_heading_node_id, absence_concept, concept, secondary_concept,
                   issue_family, observation_kind, observation_text, severity,
                   review_classification, proposed_action, proposed_language,
                   proposed_language_source, approved_drafting_evidence_id, quality,
                   created_at)
                values (:id, :organizationId, :reviewId, :runId, :consolidationKey,
                   :subjectKind, :subjectHeadingNodeId, :absenceConcept, :concept,
                   :secondaryConcept, :issueFamily, :observationKind, :observationText,
                   :severity, :reviewClassification, :proposedAction, :proposedLanguage,
                   :proposedLanguageSource, :approvedDraftingEvidenceId,
                   cast(:quality as jsonb), :createdAt)
                """)
                .param("id", finding.id())
                .param("organizationId", finding.organizationId())
                .param("reviewId", finding.reviewId())
                .param("runId", finding.runId())
                .param("consolidationKey", finding.consolidationKey())
                .param("subjectKind", finding.subjectKind().name())
                .param("subjectHeadingNodeId", finding.subjectHeadingNodeId())
                .param("absenceConcept", finding.absenceConcept() == null
                        ? null : finding.absenceConcept().name())
                .param("concept", finding.concept().name())
                .param("secondaryConcept", finding.secondaryConcept() == null
                        ? null : finding.secondaryConcept().name())
                .param("issueFamily", finding.issueFamily().name())
                .param("observationKind", finding.observationKind())
                .param("observationText", finding.observationText())
                .param("severity", finding.severity().name())
                .param("reviewClassification", finding.reviewClassification().name())
                .param("proposedAction", finding.proposedAction().name())
                .param("proposedLanguage", finding.proposedLanguage())
                .param("proposedLanguageSource", finding.proposedLanguageSource().name())
                .param("approvedDraftingEvidenceId", finding.approvedDraftingEvidenceId())
                .param("quality", json(finding.quality()))
                .param("createdAt", timestamp(finding.createdAt()))
                .update();
    }

    private void insertChildren(Finding finding) {
        var conceptOrdinal = 0;
        for (var concept : finding.concepts()) {
            jdbcClient.sql("""
                    insert into finding_concept
                      (id, organization_id, finding_id, ordinal, concept)
                    values (:id, :organizationId, :findingId, :ordinal, :concept)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", finding.organizationId())
                    .param("findingId", finding.id())
                    .param("ordinal", conceptOrdinal++)
                    .param("concept", concept.name())
                    .update();
        }
        for (var claim : finding.claims()) {
            jdbcClient.sql("""
                    insert into finding_claim
                      (id, organization_id, finding_id, ordinal, claim_kind, text, absence,
                       evidence_item_ids)
                    values (:id, :organizationId, :findingId, :ordinal, :claimKind, :text,
                       :absence, cast(:evidenceItemIds as jsonb))
                    """)
                    .param("id", claim.id())
                    .param("organizationId", finding.organizationId())
                    .param("findingId", finding.id())
                    .param("ordinal", claim.ordinal())
                    .param("claimKind", claim.claimKind().name())
                    .param("text", claim.text())
                    .param("absence", claim.absence())
                    .param("evidenceItemIds", json(claim.evidenceItemIds()))
                    .update();
        }
        var evidenceOrdinal = 0;
        for (var evidenceId : finding.evidenceItemIds()) {
            jdbcClient.sql("""
                    insert into finding_evidence
                      (id, organization_id, finding_id, evidence_item_id, ordinal)
                    values (:id, :organizationId, :findingId, :evidenceItemId, :ordinal)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", finding.organizationId())
                    .param("findingId", finding.id())
                    .param("evidenceItemId", evidenceId)
                    .param("ordinal", evidenceOrdinal++)
                    .update();
        }
        for (var signalId : finding.signalIds()) {
            jdbcClient.sql("""
                    insert into finding_signal
                      (id, organization_id, finding_id, signal_id)
                    values (:id, :organizationId, :findingId, :signalId)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", finding.organizationId())
                    .param("findingId", finding.id())
                    .param("signalId", signalId)
                    .update();
        }
    }

    @Override
    public List<Finding> findFindings(UUID organizationId, UUID runId) {
        var rows = jdbcClient.sql("""
                select id, organization_id, review_id, run_id, consolidation_key,
                       subject_kind, subject_heading_node_id, absence_concept, concept,
                       secondary_concept, issue_family, observation_kind, observation_text,
                       severity, review_classification, proposed_action, proposed_language,
                       proposed_language_source, approved_drafting_evidence_id, quality,
                       created_at
                from finding
                where run_id = :runId and organization_id = :organizationId
                order by consolidation_key
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new FindingRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("consolidation_key"),
                        FindingSubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("subject_heading_node_id", UUID.class),
                        rs.getString("absence_concept") == null ? null
                                : LegalConcept.valueOf(rs.getString("absence_concept")),
                        LegalConcept.valueOf(rs.getString("concept")),
                        rs.getString("secondary_concept") == null ? null
                                : LegalConcept.valueOf(rs.getString("secondary_concept")),
                        IssueFamily.valueOf(rs.getString("issue_family")),
                        rs.getString("observation_kind"),
                        rs.getString("observation_text"),
                        FindingSeverity.valueOf(rs.getString("severity")),
                        FindingClassification.valueOf(rs.getString("review_classification")),
                        ProposedAction.valueOf(rs.getString("proposed_action")),
                        rs.getString("proposed_language"),
                        ProposedLanguageSource.valueOf(
                                rs.getString("proposed_language_source")),
                        rs.getObject("approved_drafting_evidence_id", UUID.class),
                        map(rs.getString("quality")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();

        var concepts = children(organizationId, runId, "concept", (findingId, row) ->
                new ConceptRow(findingId, LegalConcept.valueOf(row.getString("concept"))));
        var claims = children(organizationId, runId, "claim", (findingId, row) ->
                new ClaimRow(findingId, new FindingClaim(
                        row.getObject("id", UUID.class),
                        row.getInt("ordinal"),
                        ClaimKind.valueOf(row.getString("claim_kind")),
                        row.getString("text"),
                        row.getBoolean("absence"),
                        uuidList(row.getString("evidence_item_ids")))));
        var evidence = children(organizationId, runId, "evidence", (findingId, row) ->
                new EvidenceRow(findingId, row.getObject("evidence_item_id", UUID.class)));
        var signals = children(organizationId, runId, "signal", (findingId, row) ->
                new SignalRow(findingId, row.getObject("signal_id", UUID.class)));

        var findings = new ArrayList<Finding>();
        for (var row : rows) {
            var rowConcepts = concepts.getOrDefault(row.id(), List.of()).stream()
                    .map(ConceptRow::concept).toList();
            var rowClaims = claims.getOrDefault(row.id(), List.of()).stream()
                    .map(ClaimRow::claim).toList();
            var rowEvidence = evidence.getOrDefault(row.id(), List.of()).stream()
                    .map(EvidenceRow::evidenceId).toList();
            var rowSignals = signals.getOrDefault(row.id(), List.of()).stream()
                    .map(SignalRow::signalId).toList();
            findings.add(new Finding(row.id(), row.organizationId(), row.reviewId(),
                    row.runId(), row.consolidationKey(), row.subjectKind(),
                    row.subjectHeadingNodeId(), row.absenceConcept(), row.concept(),
                    row.secondaryConcept(), row.issueFamily(), row.observationKind(),
                    row.observationText(), row.severity(), row.reviewClassification(),
                    row.proposedAction(), row.proposedLanguage(), row.proposedLanguageSource(),
                    row.approvedDraftingEvidenceId(), row.quality(), row.createdAt(),
                    rowConcepts, rowClaims, rowEvidence, rowSignals));
        }
        return List.copyOf(findings);
    }

    @Override
    public int countFindings(UUID organizationId, UUID runId) {
        return jdbcClient.sql("""
                select count(*) from finding
                where run_id = :runId and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single();
    }

    private <T> Map<UUID, List<T>> children(
            UUID organizationId, UUID runId, String child, ChildMapper<T> mapper) {
        var sql = switch (child) {
            case "concept" -> """
                    select c.finding_id, c.ordinal, c.concept
                    from finding_concept c
                    join finding f on f.id = c.finding_id
                    where f.run_id = :runId and f.organization_id = :organizationId
                    order by c.finding_id, c.ordinal
                    """;
            case "claim" -> """
                    select c.finding_id, c.id, c.ordinal, c.claim_kind, c.text, c.absence,
                           c.evidence_item_ids
                    from finding_claim c
                    join finding f on f.id = c.finding_id
                    where f.run_id = :runId and f.organization_id = :organizationId
                    order by c.finding_id, c.ordinal
                    """;
            case "evidence" -> """
                    select e.finding_id, e.evidence_item_id
                    from finding_evidence e
                    join finding f on f.id = e.finding_id
                    where f.run_id = :runId and f.organization_id = :organizationId
                    order by e.finding_id, e.ordinal
                    """;
            case "signal" -> """
                    select s.finding_id, s.signal_id
                    from finding_signal s
                    join finding f on f.id = s.finding_id
                    where f.run_id = :runId and f.organization_id = :organizationId
                    order by s.finding_id, s.signal_id
                    """;
            default -> throw new IllegalStateException("Unknown child table " + child);
        };
        var grouped = new LinkedHashMap<UUID, List<T>>();
        jdbcClient.sql(sql)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> {
                    var findingId = rs.getObject("finding_id", UUID.class);
                    grouped.computeIfAbsent(findingId, key -> new ArrayList<>())
                            .add(mapper.map(findingId, rs));
                    return null;
                })
                .list();
        return grouped;
    }

    @FunctionalInterface
    private interface ChildMapper<T> {
        T map(UUID findingId, java.sql.ResultSet row) throws java.sql.SQLException;
    }

    private record FindingRow(
            UUID id, UUID organizationId, UUID reviewId, UUID runId, String consolidationKey,
            FindingSubjectKind subjectKind, UUID subjectHeadingNodeId,
            LegalConcept absenceConcept, LegalConcept concept, LegalConcept secondaryConcept,
            IssueFamily issueFamily, String observationKind, String observationText,
            FindingSeverity severity, FindingClassification reviewClassification,
            ProposedAction proposedAction, String proposedLanguage,
            ProposedLanguageSource proposedLanguageSource, UUID approvedDraftingEvidenceId,
            Map<String, Object> quality, java.time.Instant createdAt) {
    }

    private record ConceptRow(UUID findingId, LegalConcept concept) {
    }

    private record ClaimRow(UUID findingId, FindingClaim claim) {
    }

    private record EvidenceRow(UUID findingId, UUID evidenceId) {
    }

    private record SignalRow(UUID findingId, UUID signalId) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON map", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<UUID> uuidList(String json) {
        try {
            List<String> values = json == null
                    ? List.of()
                    : objectMapper.readValue(json, List.class);
            return values.stream().map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid UUID list JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }

    private static OffsetDateTime timestamp(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
