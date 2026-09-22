package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.foundation.text.Texts;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Controlled publication: converts reviewed candidates into a coherent, versioned,
 * immutable organizational knowledge snapshot (Architecture Decision #6.10/#7.19).
 * Only APPROVED candidates may publish; each candidate publishes at most once; declared
 * supersession of a published candidate is recorded explicitly in the snapshot.
 */
public class KnowledgePublicationService {

    private final KnowledgeRepository repository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public KnowledgePublicationService(
            KnowledgeRepository repository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public KnowledgePublication publish(List<UUID> candidateIds, String note) {
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
                items.add(new PublicationItem(
                        UUID.randomUUID(), publicationId, candidate.id(), supersededId,
                        PublicationAction.SUPERSEDE));
            }
        }

        var publication = new KnowledgePublication(
                publicationId, organizationId, publicationNumber,
                Texts.normalize(note), items, now);
        repository.insertPublication(publication);
        for (var candidate : candidates) {
            repository.updateReviewState(
                    candidate.id(), ReviewStatus.PUBLISHED, candidate.editedContentText(),
                    candidate.editedPayload(), candidate.reviewNote(), candidate.reviewedAt(),
                    now);
        }
        return publication;
    }

}
