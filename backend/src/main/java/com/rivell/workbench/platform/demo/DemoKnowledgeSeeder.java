package com.rivell.workbench.platform.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rivell.workbench.knowledge.ArtifactManifest;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.ReviewCategory;
import com.rivell.workbench.knowledge.ReviewStatus;

/**
 * Seeds the knowledge scenarios through the real ingest, review and publication services:
 * one published playbook plus clause library, a second immutable playbook publication that
 * explicitly supersedes the first, an unpublished compilation, and a genuinely failed
 * artifact. Nothing is published without going through candidate review.
 */
final class DemoKnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoKnowledgeSeeder.class);
    private static final String NOTE = "Rivell demo scenario publication";

    private final KnowledgeIngestionService ingestion;
    private final KnowledgeQueryService queries;
    private final CandidateReviewService candidates;
    private final KnowledgePublicationService publications;

    DemoKnowledgeSeeder(KnowledgeIngestionService ingestion, KnowledgeQueryService queries,
            CandidateReviewService candidates, KnowledgePublicationService publications) {
        this.ingestion = ingestion;
        this.queries = queries;
        this.candidates = candidates;
        this.publications = publications;
    }

    record KnowledgeSeedResult(int sources, int publications, boolean escalationTriggerPublished) {
    }

    /**
     * Publishes the playbook and clause library together as the first publication. Findings
     * need approved drafting to propose wording, so this runs before any review analysis.
     */
    void publishBootstrap() {
        var playbook = ensureArtifact(DemoDocuments.PLAYBOOK, ArtifactType.PLAYBOOK);
        var library = ensureArtifact(DemoDocuments.CLAUSE_LIBRARY, ArtifactType.CLAUSE_LIBRARY);
        approveAll(playbook.id());
        approveAll(library.id());
        var approved = approvedIds(playbook.id());
        approved.addAll(approvedIds(library.id()));
        if (!approved.isEmpty()) {
            publications.publish(approved, Map.of(), NOTE);
        }
    }

    /** K05 and K06: an unpublished compilation and a real ingestion failure. */
    void seedDraftAndFailure() {
        var draft = ensureArtifact(DemoDocuments.DRAFT_PLAYBOOK, ArtifactType.PLAYBOOK);
        if (draft.ingestStatus() != IngestStatus.EXTRACTED) {
            throw new IllegalStateException("Draft playbook did not compile: " + draft.ingestStatus());
        }
        var unreadable = ensureArtifact(DemoDocuments.UNREADABLE_PLAYBOOK, ArtifactType.PLAYBOOK);
        if (unreadable.ingestStatus() != IngestStatus.FAILED) {
            throw new IllegalStateException(
                    "Unreadable playbook unexpectedly ingested: " + unreadable.ingestStatus());
        }
    }

    /**
     * K02: a second immutable playbook publication. Every v2 candidate whose concept the
     * first publication already covers is published with an explicit supersede item, so no
     * concept is left with two live positions.
     */
    void publishPlaybookV2() {
        var v1 = queries.listArtifacts(null).stream()
                .filter(artifact -> DemoDocuments.PLAYBOOK.equals(artifact.filename()))
                .findFirst()
                .orElseThrow();
        var v2 = ensureArtifact(DemoDocuments.PLAYBOOK_V2, ArtifactType.PLAYBOOK);
        approveAll(v2.id());
        var v2Approved = queries.getCandidates(v2.id()).stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.APPROVED)
                .toList();
        if (v2Approved.isEmpty()) {
            return;
        }
        var publishedByConcept = new LinkedHashMap<String, UUID>();
        for (var candidate : queries.getCandidates(v1.id())) {
            if (candidate.reviewStatus() == ReviewStatus.PUBLISHED
                    && candidate.effectiveConceptKey() != null) {
                publishedByConcept.putIfAbsent(candidate.effectiveConceptKey(), candidate.id());
            }
        }
        var supersede = new LinkedHashMap<UUID, UUID>();
        for (var candidate : v2Approved) {
            var previous = publishedByConcept.get(candidate.effectiveConceptKey());
            if (previous != null) {
                supersede.put(candidate.id(), previous);
            }
        }
        publications.publish(v2Approved.stream().map(KnowledgeCandidate::id).toList(),
                supersede, NOTE + " (supersedes the first playbook)");
    }

    /**
     * K07: a routine grouped-review group. Ingesting the routine playbook must leave genuinely
     * bulk-eligible candidates behind: PENDING and ROUTINE under the real categorization, with
     * an empty review-reason set. Nothing here approves, reclassifies or otherwise nudges a
     * candidate into eligibility; if the domain stops classifying these units as routine the
     * seed fails loudly rather than presenting an ineligible group as reviewable in bulk.
     */
    void seedRoutineGroup() {
        var artifact = ensureArtifact(DemoDocuments.ROUTINE_PLAYBOOK, ArtifactType.PLAYBOOK);
        if (artifact.ingestStatus() != IngestStatus.EXTRACTED) {
            throw new IllegalStateException(
                    "Routine playbook did not compile: " + artifact.ingestStatus());
        }
        var routine = queries.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .toList();
        if (routine.size() < 2) {
            throw new IllegalStateException("K07 needs at least 2 PENDING ROUTINE candidates for "
                    + "grouped approval, but the domain produced " + routine.size()
                    + " from " + DemoDocuments.ROUTINE_PLAYBOOK);
        }
        log.info("demo scenario: routine grouped review ready ({} candidates, reasons={})",
                routine.size(), routine.get(0).reviewReasons());
    }

    boolean escalationTriggerPublished() {
        return queries.listArtifacts(null).stream()
                .filter(artifact -> DemoDocuments.PLAYBOOK.equals(artifact.filename()))
                .findFirst()
                .map(artifact -> queries.getCandidates(artifact.id()).stream()
                        .anyMatch(candidate -> candidate.reviewStatus() == ReviewStatus.PUBLISHED
                                && candidate.effectiveEscalationTrigger() != null
                                && !candidate.effectiveEscalationTrigger().isBlank()))
                .orElse(false);
    }

    private KnowledgeArtifact ensureArtifact(String filename, ArtifactType type) {
        var existing = queries.listArtifacts(null).stream()
                .filter(artifact -> filename.equals(artifact.filename()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        try (var source = DemoDocuments.open(filename)) {
            var artifact = ingestion.ingest(filename, DemoDocuments.DOCX, type, manifest(), source);
            log.info("demo scenario: ingested knowledge {} ({})", filename, artifact.ingestStatus());
            return artifact;
        } catch (IOException e) {
            throw new IllegalStateException("Demo knowledge unreadable: " + filename, e);
        }
    }

    private void approveAll(UUID artifactId) {
        for (var candidate : queries.getCandidates(artifactId)) {
            if (candidate.reviewStatus() == ReviewStatus.PENDING
                    || candidate.reviewStatus() == ReviewStatus.EDITED) {
                candidates.approve(candidate.id(), "Rivell demo scenario approval");
            }
        }
    }

    private List<UUID> approvedIds(UUID artifactId) {
        var ids = new ArrayList<UUID>();
        for (var candidate : queries.getCandidates(artifactId)) {
            if (candidate.reviewStatus() == ReviewStatus.APPROVED) {
                ids.add(candidate.id());
            }
        }
        return ids;
    }

    private ArtifactManifest manifest() {
        return new ArtifactManifest(Set.of(), null, null, null, null, null, null, null,
                "Rivell local demo scenario data");
    }
}
