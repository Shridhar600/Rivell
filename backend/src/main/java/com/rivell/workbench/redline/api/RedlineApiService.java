package com.rivell.workbench.redline.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.redline.RedlineArtifactDownload;
import com.rivell.workbench.redline.RedlineExecution;
import com.rivell.workbench.redline.RedlineExecutionService;

/**
 * Redline execution HTTP surface: trigger (idempotent), execution state/report, and the
 * verified artifact download. Permissions are enforced in the execution service.
 */
public class RedlineApiService {

    private final RedlineExecutionService executionService;

    public RedlineApiService(RedlineExecutionService executionService) {
        this.executionService = executionService;
    }

    public RedlineExecutionView trigger(UUID reviewId) {
        return toView(executionService.execute(reviewId));
    }

    public RedlineExecutionView retry(UUID reviewId) {
        return toView(executionService.retry(reviewId));
    }

    public RedlineExecutionView find(UUID reviewId) {
        return toView(executionService.find(reviewId));
    }

    public RedlineArtifactDownload download(UUID reviewId) {
        return executionService.download(reviewId);
    }

    private static RedlineExecutionView toView(RedlineExecution execution) {
        var artifact = execution.artifact();
        var changes = execution.changes().stream()
                .map(change -> new RedlineExecutionChangeView(change.approvedChangeId(),
                        change.ordinal(), change.actionType().name(),
                        change.targetKind().name(), change.status().name(),
                        change.verification() == null ? null : change.verification().name(),
                        change.failureCode() == null ? null : change.failureCode().name(),
                        change.placement(), change.resolvedTarget(),
                        change.paragraphsDeleted(), change.paragraphsInserted()))
                .toList();
        return new RedlineExecutionView(execution.id(), execution.reviewId(),
                execution.changeSetId(), execution.contractId(),
                execution.contractVersionNumber(), execution.changeSetVersion(),
                execution.attemptNumber(),
                execution.status().name(),
                execution.outcome() == null ? null : execution.outcome().name(),
                execution.failureCode() == null ? null : execution.failureCode().name(),
                execution.failureDetail(), execution.rendererVersion(),
                execution.placementStrategyVersion(), execution.sourceSha256(),
                execution.changeCount(), execution.requestedBy(), execution.startedAt(),
                execution.completedAt(),
                artifact == null ? null : artifact.filename(),
                artifact == null ? null : artifact.sizeBytes(),
                artifact == null ? null : artifact.sha256(),
                execution.downloadable(), changes);
    }
}
