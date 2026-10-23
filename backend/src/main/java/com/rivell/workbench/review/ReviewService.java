package com.rivell.workbench.review;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.EnrichmentFailedException;
import com.rivell.workbench.contracts.EnrichmentInProgressException;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.SemanticsNotFoundException;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.knowledge.ApplicableKnowledgeQuery;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.retrieval.RetrievalProperties;

/**
 * Review creation: validate the contract version and enrichment truth, resolve pack
 * and "latest" dependencies exactly once, freeze the snapshot and plan, persist the
 * aggregate. Analysis execution is enqueued separately (M7 orchestration batch).
 */
public class ReviewService {

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ContractQueryService contractQueryService;
    private final ContractEnrichmentService contractEnrichmentService;
    private final KnowledgeQueryService knowledgeQueryService;
    private final ModelBindingCatalog modelBindingCatalog;
    private final EmbeddingService embeddingService;
    private final RetrievalProperties retrievalProperties;
    private final ReviewPackRegistry packs;
    private final ReviewPlanBuilder planBuilder;
    private final ReviewRepository reviewRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ReviewService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ContractQueryService contractQueryService,
            ContractEnrichmentService contractEnrichmentService,
            KnowledgeQueryService knowledgeQueryService,
            ModelBindingCatalog modelBindingCatalog,
            EmbeddingService embeddingService,
            RetrievalProperties retrievalProperties,
            ReviewPackRegistry packs,
            ReviewPlanBuilder planBuilder,
            ReviewRepository reviewRepository,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.contractQueryService = contractQueryService;
        this.contractEnrichmentService = contractEnrichmentService;
        this.knowledgeQueryService = knowledgeQueryService;
        this.modelBindingCatalog = modelBindingCatalog;
        this.embeddingService = embeddingService;
        this.retrievalProperties = retrievalProperties;
        this.packs = packs;
        this.planBuilder = planBuilder;
        this.reviewRepository = reviewRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public Review create(UUID contractId, int versionNumber, String requestedPackKey) {
        return create(contractId, versionNumber, requestedPackKey, null);
    }

    /**
     * M10 / DW-020: with a client-supplied key, creation is idempotent. A retry or a double-click
     * returns the original review instead of freezing a second snapshot, planning a second run and
     * paying for a second analysis. The replay is resolved before any work, and the unique index on
     * (organization_id, client_request_id) is the authority when two identical requests race.
     */
    /**
     * A key identifies one operation. Returning a review recorded for a different contract, version
     * or contract pack would be a confused deputy: the caller would receive a valid, fully populated
     * review built from a playbook it did not select, and would then decide, finalise and redline
     * against the wrong pack. Refusing is the only safe answer.
     */
    private Review requireSameOperation(
            Review existing, UUID contractId, int contractVersionNumber,
            String resolvedPackKey, UUID organizationId) {
        if (!existing.contractId().equals(contractId)
                || existing.contractVersionNumber() != contractVersionNumber) {
            throw new IdempotencyKeyReusedException(existing.clientRequestId());
        }
        var frozenPackKey = reviewRepository.findSnapshot(organizationId, existing.id())
                .map(ReviewSnapshot::contractPackKey)
                .orElse(null);
        if (!Objects.equals(normalize(frozenPackKey), normalize(resolvedPackKey))) {
            throw new IdempotencyKeyReusedException(existing.clientRequestId());
        }
        return existing;
    }

    public Review create(UUID contractId, int versionNumber, String requestedPackKey,
            UUID clientRequestId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_CREATE)) {
            throw new UnauthorizedReviewAccessException(identity.userId());
        }
        var version = contractQueryService.getVersionSummary(contractId, versionNumber);
        var semantics = contractEnrichmentService.getSemantics(contractId, versionNumber);
        var profile = semantics.reviewProfile();
        // Resolved before the replay check, because a key identifies one operation only when the
        // pack matches too. The pack a request resolves to is the profile's when the caller omits
        // it, so the comparison has to use the resolved value: comparing the raw request field
        // would refuse a legitimate replay of a request that relied on the profile.
        var packKey = resolvePackKey(profile.contractPackKey(), requestedPackKey);
        if (clientRequestId != null) {
            var replay = reviewRepository.findByClientRequestId(
                    identity.organizationId(), clientRequestId);
            if (replay.isPresent()) {
                return requireSameOperation(replay.get(), contractId, versionNumber, packKey,
                        identity.organizationId());
            }
        }
        requireEnrichmentReady(semantics.enrichmentStatus(), contractId, versionNumber);
        var pack = packKey == null ? null : packs.find(packKey)
                .orElseThrow(() -> new InvalidReviewConfigurationException(
                        "That review pack is not available."));

        var organizationId = identity.organizationId();
        // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would be
        // stored rounded, so the returned review would not match a later read of it.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var effectiveOn = LocalDate.now(clock);
        var knowledgePinNumber = knowledgeQueryService.maxPublicationNumberForOrganization(organizationId);
        // The snapshot freezes the playbooks and clause libraries the analysis may use, resolved by
        // the same rule retrieval applies. A review created before this freeze has no such list and
        // must never be recomputed at read time.
        var applicableKnowledge = knowledgeQueryService.applicableKnowledgeForOrganization(
                new ApplicableKnowledgeQuery(
                        organizationId, knowledgePinNumber, profile.contractType().name(), packKey,
                        profile.ourSideRole() == null ? null : profile.ourSideRole().name(),
                        profile.paperSource() == null ? null : profile.paperSource().name(),
                        profile.jurisdiction() == Jurisdiction.UNSPECIFIED
                                ? null : profile.jurisdiction().name(),
                        effectiveOn));
        var reviewId = UUID.randomUUID();
        var bindings = modelBindingCatalog.current();
        var snapshot = new ReviewSnapshot(
                reviewId, organizationId, contractId, version.versionId(), versionNumber,
                profile.contractType(), profile.ourSideRole(), profile.paperSource(),
                profile.jurisdiction(), packKey,
                pack == null ? null : pack.version(),
                semantics.id(), semantics.pipelineVersion(), SemanticsDigest.of(semantics),
                knowledgePinNumber,
                effectiveOn, ReviewPipeline.VERSION,
                retrievalProperties.indexVersion(),
                embeddingService.isConfigured() ? embeddingService.modelIdentity() : null,
                new ModelPolicyIdentity(
                        bindings.decisionProvider().providerId(),
                        bindings.decisionProvider().modelAlias(),
                        bindings.generativeProvider().providerId(),
                        bindings.generativeProvider().modelAlias()),
                ReviewEngine.V2,
                applicableKnowledge,
                now);
        var review = Review.analysisQueued(
                reviewId, organizationId, contractId, version.versionId(), versionNumber,
                identity.userId(), now, clientRequestId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                reviewRepository.create(review, snapshot, planBuilder.build(reviewId,
                        organizationId, snapshot, semantics));
                eventPublisher.publishEvent(new ReviewCreatedEvent(organizationId, reviewId));
            });
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // M10 review finding: the pre-check cannot see a concurrent identical request, so two
            // simultaneous submits both missed it and the loser's insert violated the unique index.
            // The index enforces the invariant but does not return the winner, so read it back: the
            // client asked for an idempotent create and must get the original review, not a 500.
            if (clientRequestId == null) {
                throw conflict;
            }
            var winner = reviewRepository.findByClientRequestId(organizationId, clientRequestId);
            if (winner.isEmpty()) {
                throw conflict;
            }
            // The read-back is a replay like any other, so it goes through the same check: two
            // racing requests can carry different packs, and the loser must not be handed the
            // winner's review as if it had asked for it.
            return requireSameOperation(winner.get(), contractId, versionNumber, packKey,
                    organizationId);
        }
        return review;
    }

    private static void requireEnrichmentReady(
            EnrichmentStatus status, UUID contractId, int versionNumber) {
        switch (status) {
            case READY -> {
            }
            case ENRICHING -> throw new EnrichmentInProgressException(contractId, versionNumber);
            case FAILED -> throw new EnrichmentFailedException(contractId, versionNumber);
            case NOT_ENRICHED -> throw new SemanticsNotFoundException(contractId, versionNumber);
        }
    }

    private String resolvePackKey(String profilePackKey, String requestedPackKey) {
        var declared = normalize(profilePackKey);
        var requested = normalize(requestedPackKey);
        if (declared != null && requested != null && !declared.equals(requested)) {
            throw new InvalidReviewConfigurationException(
                    "This contract is set up for a different review pack than the one you "
                            + "chose.");
        }
        return requested != null ? requested : declared;
    }

    private static String normalize(String key) {
        return key == null || key.isBlank() ? null : key.trim();
    }
}
