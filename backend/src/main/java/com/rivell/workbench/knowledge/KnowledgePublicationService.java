package com.rivell.workbench.knowledge;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.Texts;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Controlled publication: converts reviewed candidates into a coherent, versioned,
 * immutable organizational knowledge snapshot (Architecture Decision #6.10/#7.19).
 * Only APPROVED candidates may publish; each candidate publishes at most once; declared
 * supersession of a published candidate is recorded explicitly in the snapshot.
 * The publication record, its items, and the candidates' PUBLISHED transitions commit
 * as one atomic unit, so an interrupted publication persists nothing.
 */
public class KnowledgePublicationService {

    /** Bounded: the race window is one insert, so a second attempt is normally enough. */
    private static final int MAX_PUBLICATION_NUMBER_ATTEMPTS = 3;

    private final KnowledgeRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public KnowledgePublicationService(
            KnowledgeRepository repository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
    }

    public KnowledgePublication publish(
            List<UUID> candidateIds, java.util.Map<UUID, UUID> supersededCandidates, String note) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_PUBLISH)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        if (candidateIds == null || candidateIds.isEmpty()) {
            throw new IllegalArgumentException("A publication requires at least one candidate");
        }
        var organizationId = identity.organizationId();

        var uniqueIds = new LinkedHashSet<UUID>(candidateIds);
        var candidates = new ArrayList<KnowledgeCandidate>(uniqueIds.size());
        for (var candidateId : uniqueIds) {
            var candidate = repository.findCandidate(candidateId, organizationId)
                    .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
            if (candidate.reviewStatus() != ReviewStatus.APPROVED) {
                throw new InvalidPublicationException(candidateId,
                        "only APPROVED candidates can be published (current state: "
                                + candidate.reviewStatus() + ")");
            }
            if (supersededCandidates != null && supersededCandidates.containsKey(candidateId)) {
                candidate = candidate.withSupersedingCandidateId(
                        supersededCandidates.get(candidateId), organizationId);
            }
            candidates.add(candidate);
        }

        var now = Instant.now();
        var publicationId = UUID.randomUUID();
        var publicationNumber = repository.nextPublicationNumber(organizationId);
        var items = new ArrayList<PublicationItem>();
        for (var candidate : candidates) {
            items.add(new PublicationItem(
                    UUID.randomUUID(), publicationId, candidate.id(), null,
                    PublicationAction.ADD));
            var supersededId = candidate.supersedingCandidateId();
            if (supersededId != null) {
                var superseded = repository.findCandidate(supersededId, organizationId)
                        .orElseThrow(() -> new InvalidPublicationException(candidate.id(),
                                "superseded candidate does not exist"));
                if (!superseded.isPublished()) {
                    throw new InvalidPublicationException(candidate.id(),
                            "superseded candidate must itself be published");
                }
                if (superseded.effectiveConceptKey() != null
                        && candidate.effectiveConceptKey() != null
                        && !superseded.effectiveConceptKey().equals(candidate.effectiveConceptKey())) {
                    throw new InvalidPublicationException(candidate.id(),
                            "superseded candidate must cover the same legal concept");
                }
                items.add(new PublicationItem(
                        UUID.randomUUID(), publicationId, candidate.id(), supersededId,
                        PublicationAction.SUPERSEDE));
            }
        }

        var superseded = new java.util.LinkedHashMap<UUID, UUID>();
        for (var item : items) {
            if (item.action() == PublicationAction.SUPERSEDE && item.supersededCandidateId() != null) {
                superseded.put(item.candidateId(), item.supersededCandidateId());
            }
        }

        // M10 / DW-001: publication numbers are allocated as max + 1 with no lock, so two concurrent
        // publications can pick the same number. The unique constraint keeps numbering uncorrupt, but
        // the loser used to fail outright and the user had to retry blind. The whole transactional
        // unit is retried against a freshly allocated number; the event is published inside the
        // transaction, so a losing attempt emits nothing.
        DataIntegrityViolationException lastConflict = null;
        for (var attempt = 1; attempt <= MAX_PUBLICATION_NUMBER_ATTEMPTS; attempt++) {
            var number = repository.nextPublicationNumber(organizationId);
            var publication = new KnowledgePublication(
                    publicationId, organizationId, number, Texts.normalize(note), items, now);
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    repository.insertPublication(publication);
                    for (var candidate : candidates) {
                        repository.updateReviewState(
                                candidate.id(), organizationId, candidate.reviewStatus(),
                                ReviewStatus.PUBLISHED, candidate.editedContentText(),
                                candidate.editedPayload(), candidate.editedSemanticFields(),
                                candidate.scope(), candidate.supersedingCandidateId(),
                                candidate.reviewNote(), candidate.reviewedAt(), now);
                    }
                    // published inside the transaction: AFTER_COMMIT listeners fire post-commit
                    eventPublisher.publishEvent(new KnowledgePublishedEvent(
                            organizationId, publicationId, number,
                            candidates.stream().map(KnowledgeCandidate::id).toList(),
                            java.util.Collections.unmodifiableMap(superseded)));
                });
                return publication;
            } catch (DataIntegrityViolationException conflict) {
                if (!isPublicationNumberConflict(conflict)) {
                    throw conflict;
                }
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }

    /**
     * Distinguishes "someone else took this publication number" from every other integrity failure.
     * Only the first is retryable; retrying a candidate-state conflict would hide a real defect.
     */
    private static boolean isPublicationNumberConflict(
            DataIntegrityViolationException conflict) {
        var message = conflict.getMostSpecificCause().getMessage();
        return message != null && message.contains("uq_knowledge_publication_number")
                || message != null && message.contains("knowledge_publication_organization_id_publication_number_key");
    }

}
