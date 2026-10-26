package com.rivell.workbench.knowledge;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
            throw new IllegalArgumentException(
                    "Choose at least one entry for the new knowledge version.");
        }
        var organizationId = identity.organizationId();

        var uniqueIds = new LinkedHashSet<UUID>(candidateIds);
        var candidates = new ArrayList<KnowledgeCandidate>(uniqueIds.size());
        for (var candidateId : uniqueIds) {
            var candidate = repository.findCandidate(candidateId, organizationId)
                    .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
            if (candidate.reviewStatus() != ReviewStatus.APPROVED) {
                throw new InvalidPublicationException(candidateId,
                        "Only approved entries can go into a new knowledge version. "
                                + "This entry is " + InvalidCandidateReviewStateException
                                        .describe(candidate.reviewStatus()) + ".");
            }
            if (supersededCandidates != null && supersededCandidates.containsKey(candidateId)) {
                candidate = candidate.withSupersedingCandidateId(
                        supersededCandidates.get(candidateId), organizationId);
            }
            candidates.add(candidate);
        }

        // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would be
        // stored rounded, so the returned publication would not match a later read of it.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var publicationId = UUID.randomUUID();
        var items = new ArrayList<PublicationItem>();
        for (var candidate : candidates) {
            items.add(new PublicationItem(
                    UUID.randomUUID(), publicationId, candidate.id(), null,
                    PublicationAction.ADD));
            var supersededId = candidate.supersedingCandidateId();
            if (supersededId != null) {
                var superseded = repository.findCandidate(supersededId, organizationId)
                        .orElseThrow(() -> new InvalidPublicationException(candidate.id(),
                                "The entry this one replaces no longer exists."));
                if (!superseded.isPublished()) {
                    throw new InvalidPublicationException(candidate.id(),
                            "The entry this one replaces must already be published.");
                }
                if (superseded.effectiveConceptKey() != null
                        && candidate.effectiveConceptKey() != null
                        && !superseded.effectiveConceptKey().equals(candidate.effectiveConceptKey())) {
                    throw new InvalidPublicationException(candidate.id(),
                            "The entry this one replaces must cover the same topic.");
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

        // Publication numbers are allocated as max + 1 with no lock, so two concurrent
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
                        // The compare-and-set is the integrity boundary of this transaction. An
                        // edit committed after validation demoted the candidate to EDITED, so
                        // this matches no row: throwing rolls the publication back rather than
                        // committing items for a candidate that was never approved in this state.
                        // The conflict is surfaced, never retried, because a retry would publish
                        // whatever the edit produced without the approval that was validated.
                        var applied = repository.updateReviewState(
                                candidate.id(), organizationId, candidate.reviewStatus(),
                                ReviewStatus.PUBLISHED, candidate.editedContentText(),
                                candidate.editedTitle(), candidate.editedPayload(), candidate.editedSemanticFields(),
                                candidate.scope(), candidate.supersedingCandidateId(),
                                candidate.reviewNote(), candidate.reviewedAt(), now,
                                candidate.conflictSignals(), candidate.reviewCategory(),
                                candidate.reviewReasons());
                        if (!applied) {
                            throw new InvalidPublicationException(candidate.id(),
                                    "This entry changed while the knowledge version was being "
                                            + "created and is no longer approved.");
                        }
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
        // The constraint is the inline UNIQUE (organization_id, publication_number) declared in V4,
        // so PostgreSQL names it after the table and columns. There is no named constraint to match.
        var message = conflict.getMostSpecificCause().getMessage();
        return message != null
                && message.contains("knowledge_publication_organization_id_publication_number_key");
    }

}
