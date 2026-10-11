package com.rivell.workbench.redline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractNotFoundException;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.ContractVersionNotFoundException;
import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.Hashes;
import com.rivell.workbench.foundation.StorageKey;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic redline execution: resolves the exact frozen source of the frozen
 * approved-change set, builds an anchor plan, renders tracked changes, verifies the
 * round trip, and persists one canonical execution per change set. Zero model calls.
 */
public class RedlineExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RedlineExecutionService.class);

    public static final String AUTHOR = "Rivell";

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final RedlineExecutionRepository executionRepository;
    private final ContractQueryService contractQueryService;
    private final DocumentStorage documentStorage;
    private final RedlineRenderer renderer;
    private final RedlineVerifier verifier;
    private final RedlinePlanResolver planResolver;
    private final Clock clock;

    public RedlineExecutionService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ApprovedChangeSetRepository changeSetRepository,
            RedlineExecutionRepository executionRepository,
            ContractQueryService contractQueryService,
            DocumentStorage documentStorage,
            RedlineRenderer renderer,
            RedlineVerifier verifier,
            RedlinePlanResolver planResolver,
            Clock clock) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.changeSetRepository = changeSetRepository;
        this.executionRepository = executionRepository;
        this.contractQueryService = contractQueryService;
        this.documentStorage = documentStorage;
        this.renderer = renderer;
        this.verifier = verifier;
        this.planResolver = planResolver;
        this.clock = clock;
    }

    public RedlineExecution execute(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_REDLINE)) {
            throw new UnauthorizedRedlineAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var changeSet = changeSetRepository.findByReview(organizationId, reviewId)
                .orElseThrow(RedlineNotAvailableException::new);
        var existing = executionRepository.findByChangeSet(organizationId, changeSet.id());
        if (existing.isPresent()) {
            return requireSuccessful(existing.get());
        }
        return requireSuccessful(
                attempt(changeSet, organizationId, identity.userId(), 1, clock.instant()));
    }

    /**
     * Retries a persisted FAILED redline attempt.
     *
     * <p>The frozen ApprovedChangeSet is re-read and re-executed exactly as it stands: no
     * re-decision, no re-finalization, and no new legal reasoning. The prior FAILED attempt is
     * left byte-for-byte intact and this appends the next numbered attempt, so the recorded
     * failure stays available instead of being rewritten into a success.
     *
     * <p>Deterministic inputs mean a retry usually reproduces the same failure, and that is
     * the honest outcome: this is a re-run, not an escape hatch. It can succeed where the
     * first attempt failed for a reason outside the frozen inputs, such as a contract
     * semantics correction that has since been reverted.
     *
     * <p>Only a recorded failure is retriable. A review with no execution at all has not
     * failed, so it is generated with the ordinary trigger rather than retried.
     */
    public RedlineExecution retry(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_REDLINE)) {
            throw new UnauthorizedRedlineAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var changeSet = changeSetRepository.findByReview(organizationId, reviewId)
                .orElseThrow(RedlineNotAvailableException::new);
        var latest = executionRepository.findByChangeSet(organizationId, changeSet.id())
                .orElseThrow(() -> new RedlineRetryNotAvailableException(
                        "No redline execution exists to retry; generate the redline first."));
        if (latest.status() != RedlineExecutionStatus.FAILED) {
            throw new RedlineRetryNotAvailableException(
                    "Only a FAILED redline execution can be retried.");
        }
        return requireSuccessful(attempt(changeSet, organizationId, identity.userId(),
                latest.attemptNumber() + 1, clock.instant()));
    }

    /**
     * One numbered attempt at the frozen change set. Shared by the initial trigger and by
     * retry so both paths render and verify identically; there is no second execution route.
     */
    private RedlineExecution attempt(ApprovedChangeSet changeSet, UUID organizationId,
            UUID requestedBy, int attemptNumber, java.time.Instant startedAt) {
        log.info("Redline execution started reviewId={} changeSetId={} attempt={} organizationId={}",
                changeSet.reviewId(), changeSet.id(), attemptNumber, organizationId);
        var execution = semanticallyDrifted(changeSet, organizationId)
                ? fail(changeSet, organizationId, requestedBy, attemptNumber, startedAt,
                        RedlineFailureCode.APPROVED_SEMANTICS_DRIFT, null,
                        "The contract semantics changed after this review was approved.",
                        List.of(), null)
                : run(changeSet, organizationId, requestedBy, attemptNumber, startedAt);
        if (execution.status() == RedlineExecutionStatus.FAILED) {
            log.info("Redline execution failed reviewId={} executionId={} attempt={} code={}",
                    changeSet.reviewId(), execution.id(), attemptNumber, execution.failureCode());
        } else {
            log.info("Redline execution completed reviewId={} executionId={} attempt={} outcome={}",
                    changeSet.reviewId(), execution.id(), attemptNumber, execution.outcome());
        }
        return execution;
    }

    public RedlineExecution find(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_READ)) {
            throw new UnauthorizedRedlineAccessException(identity.userId());
        }
        return executionRepository.findByReview(identity.organizationId(), reviewId)
                .orElseThrow(RedlineExecutionNotFoundException::new);
    }

    public RedlineArtifactDownload download(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_READ)) {
            throw new UnauthorizedRedlineAccessException(identity.userId());
        }
        var execution = executionRepository
                .findByReview(identity.organizationId(), reviewId)
                .orElseThrow(RedlineExecutionNotFoundException::new);
        if (!execution.downloadable()) {
            throw new RedlineArtifactUnavailableException(execution.status(), execution.outcome());
        }
        var artifact = execution.artifact();
        try (InputStream content = documentStorage.open(new StorageKey(artifact.storageKey()))) {
            var bytes = content.readAllBytes();
            if (!Hashes.sha256Hex(bytes).equals(artifact.sha256())) {
                throw new IllegalStateException(
                        "The stored redline artifact does not match its recorded hash");
            }
            return new RedlineArtifactDownload(artifact.filename(), artifact.contentType(),
                    bytes);
        } catch (IOException e) {
            throw new IllegalStateException("The redline artifact could not be read", e);
        }
    }

    private RedlineExecution run(
            ApprovedChangeSet changeSet, UUID organizationId, UUID requestedBy,
            int attemptNumber, java.time.Instant startedAt) {
        if (changeSet.changes().isEmpty()) {
            return persist(completedNoChanges(
                    changeSet, organizationId, requestedBy, attemptNumber, startedAt));
        }

        var version = versionSummary(changeSet, organizationId);
        if (version == null) {
            throw new RedlineSourceUnavailableException(
                    RedlineFailureCode.SOURCE_DOCUMENT_UNAVAILABLE,
                    "The frozen source document is unavailable "
                            + "(SOURCE_DOCUMENT_UNAVAILABLE).");
        }
        if (!version.versionId().equals(changeSet.contractVersionId())) {
            throw new RedlineSourceUnavailableException(
                    RedlineFailureCode.SOURCE_VERSION_MISMATCH,
                    "The frozen source version no longer matches the approved change set "
                            + "(SOURCE_VERSION_MISMATCH).");
        }

        byte[] sourceBytes;
        String sourceSha;
        try {
            var source = contractQueryService.getSourceForOrganization(organizationId,
                    changeSet.contractId(), changeSet.contractVersionNumber());
            sourceSha = source.sha256();
            sourceBytes = readStorage(source.storageKey());
        } catch (ContractVersionNotFoundException e) {
            throw new RedlineSourceUnavailableException(
                    RedlineFailureCode.SOURCE_DOCUMENT_UNAVAILABLE,
                    "The frozen source document is unavailable "
                            + "(SOURCE_DOCUMENT_UNAVAILABLE).");
        }
        if (!Hashes.sha256Hex(sourceBytes).equals(sourceSha)) {
            throw new RedlineSourceUnavailableException(
                    RedlineFailureCode.SOURCE_INTEGRITY_MISMATCH,
                    "The frozen source document failed its integrity check "
                            + "(SOURCE_INTEGRITY_MISMATCH).");
        }

        DocumentExecutionPlan plan;
        try {
            plan = planResolver.resolve(changeSet,
                    contractQueryService.getStructureForOrganization(organizationId,
                            changeSet.contractId(), changeSet.contractVersionNumber()),
                    effectiveConcepts(organizationId, changeSet),
                    AUTHOR, startedAt, sourceSha);
        } catch (RedlinePlanException e) {
            // Plan and render messages name the change ordinal and the reason, never document text.
            return fail(changeSet, organizationId, requestedBy, attemptNumber, startedAt, e.code(),
                    e.approvedChangeId(), e.getMessage(), List.of(), sourceSha);
        }

        RenderedRedline rendered;
        try {
            rendered = renderer.render(sourceBytes, plan);
        } catch (RedlineRenderException e) {
            return fail(changeSet, organizationId, requestedBy, attemptNumber, startedAt, e.code(),
                    e.approvedChangeId(), e.getMessage(), List.of(), sourceSha);
        }

        var verification = verifier.verify(sourceBytes, rendered.bytes(), plan,
                rendered.changes());
        if (!verification.passed()) {
            return fail(changeSet, organizationId, requestedBy, attemptNumber, startedAt,
                    verification.failureCode(), null,
                    verification.failureDetail(), verification.changes(), sourceSha);
        }

        var artifactId = UUID.randomUUID();
        var storageKey = organizationId + "/redline/" + artifactId + ".docx";
        storeArtifact(storageKey, rendered.bytes());
        try {
            var executionId = UUID.randomUUID();
            var completed = completedExecution(executionId, changeSet, organizationId,
                    attemptNumber,
                    requestedBy, startedAt, sourceSha, plan, rendered, verification,
                    artifactId, storageKey);
            if (executionRepository.insert(completed)) {
                return completed;
            }
            deleteQuietly(storageKey);
            return executionRepository.findByChangeSet(organizationId, changeSet.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "A concurrent redline execution won the race but could not "
                                    + "be read back"));
        } catch (RuntimeException e) {
            deleteQuietly(storageKey);
            throw e;
        }
    }

    private RedlineExecution completedExecution(
            UUID executionId,
            ApprovedChangeSet changeSet,
            UUID organizationId,
            int attemptNumber,
            UUID requestedBy,
            java.time.Instant startedAt,
            String sourceSha,
            DocumentExecutionPlan plan,
            RenderedRedline rendered,
            VerificationReport verification,
            UUID artifactId,
            String storageKey) {
        var verificationByChange = new HashMap<UUID, ChangeVerification>();
        for (var result : verification.changes()) {
            verificationByChange.put(result.approvedChangeId(), result);
        }
        var operationByChange = new HashMap<UUID, PlannedDocumentOperation>();
        for (var operation : plan.operations()) {
            operationByChange.put(operation.approvedChangeId(), operation);
        }
        var changeById = new HashMap<UUID, ApprovedChange>();
        for (var change : changeSet.changes()) {
            changeById.put(change.id(), change);
        }
        var changes = new ArrayList<RedlineExecutionChange>();
        for (var report : rendered.changes()) {
            var operation = operationByChange.get(report.approvedChangeId());
            var change = changeById.get(report.approvedChangeId());
            var result = verificationByChange.get(report.approvedChangeId());
            if (result == null || result.status() != RedlineVerificationStatus.VERIFIED) {
                throw new IllegalStateException(
                        "A rendered change has no verified round-trip result");
            }
            changes.add(new RedlineExecutionChange(UUID.randomUUID(), organizationId,
                    executionId, report.approvedChangeId(), report.ordinal(),
                    operation.actionType(), change.targetKind(),
                    RedlineChangeStatus.APPLIED, result.status(), null,
                    operation.placementLabel(), resolvedTarget(operation),
                    report.paragraphsDeleted(), report.paragraphsInserted(),
                    report.revisionIdStart(), report.revisionIdEnd()));
        }
        var artifact = new RedlineArtifact(artifactId, organizationId, executionId,
                filename(organizationId, changeSet), RedlineArtifact.DOCX_CONTENT_TYPE,
                rendered.bytes().length, rendered.sha256(), storageKey, clock.instant());
        return new RedlineExecution(executionId, organizationId, changeSet.reviewId(),
                changeSet.id(), attemptNumber, changeSet.contractId(),
                changeSet.contractVersionId(),
                changeSet.contractVersionNumber(), changeSet.changeSetVersion(),
                RedlineExecutionStatus.COMPLETED, RedlineOutcome.REDLINE_GENERATED,
                null, null, RedlinePlanResolver.RENDERER_VERSION,
                InsertPlacementStrategy.VERSION, sourceSha, changeSet.semanticsDigest(),
                changeSet.changes().size(),
                requestedBy, startedAt, clock.instant(), changes, artifact);
    }

    private RedlineExecution completedNoChanges(
            ApprovedChangeSet changeSet, UUID organizationId, UUID requestedBy,
            int attemptNumber, java.time.Instant startedAt) {
        return new RedlineExecution(UUID.randomUUID(), organizationId, changeSet.reviewId(),
                changeSet.id(), attemptNumber, changeSet.contractId(),
                changeSet.contractVersionId(),
                changeSet.contractVersionNumber(), changeSet.changeSetVersion(),
                RedlineExecutionStatus.COMPLETED, RedlineOutcome.NO_CHANGES, null, null,
                RedlinePlanResolver.RENDERER_VERSION, InsertPlacementStrategy.VERSION,
                null, changeSet.semanticsDigest(), 0,
                requestedBy, startedAt, clock.instant(), List.of(), null);
    }

    private RedlineExecution fail(
            ApprovedChangeSet changeSet,
            UUID organizationId,
            UUID requestedBy,
            int attemptNumber,
            java.time.Instant startedAt,
            RedlineFailureCode code,
            UUID failedChangeId,
            String detail,
            List<ChangeVerification> verifications,
            String sourceSha) {
        var executionId = UUID.randomUUID();
        var verificationByChange = new HashMap<UUID, ChangeVerification>();
        for (var verification : verifications) {
            verificationByChange.put(verification.approvedChangeId(), verification);
        }
        var changes = new ArrayList<RedlineExecutionChange>();
        for (var change : changeSet.changes()) {
            var verification = verificationByChange.get(change.id());
            RedlineChangeStatus status;
            RedlineVerificationStatus verificationStatus = null;
            RedlineFailureCode changeFailure = null;
            if (verification != null) {
                status = RedlineChangeStatus.APPLIED;
                verificationStatus = verification.status();
            } else if (failedChangeId != null && failedChangeId.equals(change.id())) {
                status = RedlineChangeStatus.FAILED;
                changeFailure = code;
            } else {
                status = RedlineChangeStatus.NOT_APPLIED;
            }
            changes.add(new RedlineExecutionChange(UUID.randomUUID(), organizationId,
                    executionId, change.id(), change.ordinal(), change.actionType(),
                    change.targetKind(), status, verificationStatus, changeFailure, null,
                    null, null, null, null, null));
        }
        var execution = new RedlineExecution(executionId, organizationId, changeSet.reviewId(),
                changeSet.id(), attemptNumber, changeSet.contractId(),
                changeSet.contractVersionId(),
                changeSet.contractVersionNumber(), changeSet.changeSetVersion(),
                RedlineExecutionStatus.FAILED, null, code, sanitized(detail),
                RedlinePlanResolver.RENDERER_VERSION, InsertPlacementStrategy.VERSION,
                sourceSha, changeSet.semanticsDigest(), changeSet.changes().size(),
                requestedBy, startedAt, clock.instant(), changes, null);
        return persist(execution);
    }

    private RedlineExecution persist(RedlineExecution execution) {
        if (executionRepository.insert(execution)) {
            return execution;
        }
        return executionRepository
                .findByChangeSet(execution.organizationId(), execution.changeSetId())
                .orElseThrow(() -> new IllegalStateException(
                        "A concurrent redline execution won the race but could not be "
                                + "read back"));
    }

    private RedlineExecution requireSuccessful(RedlineExecution execution) {
        if (execution.status() == RedlineExecutionStatus.FAILED) {
            throw new RedlineExecutionFailedException(execution);
        }
        return execution;
    }

    private com.rivell.workbench.contracts.ContractVersionSummary versionSummary(
            ApprovedChangeSet changeSet, UUID organizationId) {
        try {
            return contractQueryService.getVersionSummaryForOrganization(organizationId,
                    changeSet.contractId(), changeSet.contractVersionNumber());
        } catch (ContractVersionNotFoundException | ContractNotFoundException e) {
            return null;
        }
    }

    /**
     * Placement for an INSERT is derived from the effective concepts the review planned against,
     * so a post-approval correction or re-enrichment would silently reposition the redline in a
     * document a human already approved. Comparing the digest of the live semantics against the
     * one frozen at finalization makes that drift visible and stops the execution before any
     * rendering work. An already-verified artifact is never invalidated by this: the existing
     * execution is returned above, before this check runs.
     */
    private boolean semanticallyDrifted(ApprovedChangeSet changeSet, UUID organizationId) {
        // Scoped to the change types that actually consume effective concepts. A REPLACE or DELETE
        // resolves against the document structure and the approved language alone, so a routine
        // post-approval correction such as fixing a party role would otherwise permanently block a
        // legitimate redline for a reason that cannot affect it. The failed execution is also a
        // permanent record, which made an over-broad guard an availability dead end rather than a
        // safety property.
        var dependsOnConcepts = changeSet.changes().stream()
                .anyMatch(change -> change.actionType() == ChangeActionType.INSERT);
        if (!dependsOnConcepts) {
            return false;
        }
        var live = contractQueryService.getSemanticsForOrganization(organizationId,
                changeSet.contractId(), changeSet.contractVersionNumber());
        if (live.isEmpty()) {
            // M10 review finding: this failed open. With no live semantics there is nothing to compare,
            // so the guard reported "not drifted" and placement proceeded with an empty concept map -
            // exactly the unverified placement the check exists to prevent. Absence of the thing being
            // verified is not evidence that it verified.
            return true;
        }
        return !SemanticsDigest.of(live.get()).equals(changeSet.semanticsDigest());
    }

    private Map<UUID, ConceptKey> effectiveConcepts(
            UUID organizationId, ApprovedChangeSet changeSet) {
        var concepts = new HashMap<UUID, ConceptKey>();
        contractQueryService
                .getSemanticsForOrganization(organizationId, changeSet.contractId(),
                        changeSet.contractVersionNumber())
                .ifPresent(semantics -> semantics.clauses().forEach(clause ->
                        clause.effectivePrimaryConcept().ifPresent(
                                concept -> concepts.put(clause.headingNodeId(), concept))));
        return concepts;
    }

    private String resolvedTarget(PlannedDocumentOperation operation) {
        return operation.targetLabel();
    }

    private byte[] readStorage(String storageKey) {
        try (InputStream content = documentStorage.open(new StorageKey(storageKey))) {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The frozen source document could not be read", e);
        }
    }

    private void storeArtifact(String storageKey, byte[] bytes) {
        try {
            documentStorage.store(new StorageKey(storageKey), new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            throw new RedlineArtifactStoreException(
                    "The verified redline artifact could not be stored.", e);
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            documentStorage.delete(new StorageKey(storageKey));
        } catch (IOException ignored) {
            // Orphan cleanup is best-effort; the canonical execution is unaffected.
        }
    }

    private String filename(UUID organizationId, ApprovedChangeSet changeSet) {
        String title = null;
        try {
            title = contractQueryService
                    .getContractForOrganization(organizationId, changeSet.contractId()).title();
        } catch (ContractNotFoundException ignored) {
            // fall back to the generic name
        }
        var base = title == null || title.isBlank() ? "contract" : title;
        var safe = base.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+", "").replaceAll("-+$", "");
        if (safe.isBlank()) {
            safe = "contract";
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return safe + "-v" + changeSet.contractVersionNumber() + "-redline.docx";
    }

    private static String sanitized(String detail) {
        if (detail == null) {
            return null;
        }
        var single = detail.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) : single;
    }
}
