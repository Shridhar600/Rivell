package com.rivell.workbench.review;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.EnrichmentFailedException;
import com.rivell.workbench.contracts.EnrichmentInProgressException;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.SemanticsNotFoundException;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
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
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_CREATE)) {
            throw new UnauthorizedReviewAccessException(identity.userId());
        }
        var version = contractQueryService.getVersionSummary(contractId, versionNumber);
        var semantics = contractEnrichmentService.getSemantics(contractId, versionNumber);
        requireEnrichmentReady(semantics.enrichmentStatus(), contractId, versionNumber);
        var profile = semantics.reviewProfile();
        var packKey = resolvePackKey(profile.contractPackKey(), requestedPackKey);
        var pack = packKey == null ? null : packs.find(packKey)
                .orElseThrow(() -> new InvalidReviewConfigurationException(
                        "Unknown contract pack: " + packKey));

        var organizationId = identity.organizationId();
        var now = clock.instant();
        var reviewId = UUID.randomUUID();
        var bindings = modelBindingCatalog.current();
        var snapshot = new ReviewSnapshot(
                reviewId, organizationId, contractId, version.versionId(), versionNumber,
                profile.contractType(), profile.ourSideRole(), profile.paperSource(),
                profile.jurisdiction(), packKey,
                pack == null ? null : pack.version(),
                semantics.id(), semantics.pipelineVersion(), SemanticsDigest.of(semantics),
                knowledgeQueryService.maxPublicationNumber(),
                LocalDate.now(clock), ReviewPipeline.VERSION,
                retrievalProperties.indexVersion(),
                embeddingService.isConfigured() ? embeddingService.modelIdentity() : null,
                new ModelPolicyIdentity(
                        bindings.decisionProvider().providerId(),
                        bindings.decisionProvider().modelAlias(),
                        bindings.generativeProvider().providerId(),
                        bindings.generativeProvider().modelAlias()),
                now);
        var review = Review.analysisQueued(
                reviewId, organizationId, contractId, version.versionId(), versionNumber,
                identity.userId(), now);
        transactionTemplate.executeWithoutResult(status -> {
            reviewRepository.create(review, snapshot, planBuilder.build(reviewId,
                    organizationId, snapshot, semantics));
            eventPublisher.publishEvent(new ReviewCreatedEvent(organizationId, reviewId));
        });
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
                    "Review profile declares contract pack " + declared
                            + " but the review requested " + requested);
        }
        return requested != null ? requested : declared;
    }

    private static String normalize(String key) {
        return key == null || key.isBlank() ? null : key.trim();
    }
}
