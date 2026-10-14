package com.rivell.workbench.platform.api;

import java.io.IOException;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.rivell.workbench.contracts.ContractNotFoundException;
import com.rivell.workbench.contracts.ContractNotReadableException;
import com.rivell.workbench.contracts.ContractVersionNotFoundException;
import com.rivell.workbench.contracts.EnrichmentFailedException;
import com.rivell.workbench.contracts.EnrichmentInProgressException;
import com.rivell.workbench.contracts.InvalidCorrectionException;
import com.rivell.workbench.contracts.SemanticsNotFoundException;
import com.rivell.workbench.contracts.UnauthorizedContractAccessException;
import com.rivell.workbench.contracts.UploadLimitExceededException;
import com.rivell.workbench.knowledge.ConceptClassificationUnavailableException;
import com.rivell.workbench.knowledge.InvalidCandidateReviewStateException;
import com.rivell.workbench.knowledge.InvalidPublicationException;
import com.rivell.workbench.knowledge.KnowledgeArtifactNotFoundException;
import com.rivell.workbench.knowledge.KnowledgeCandidateNotFoundException;
import com.rivell.workbench.knowledge.KnowledgeUnitNotFoundException;
import com.rivell.workbench.knowledge.KnowledgeUploadLimitExceededException;
import com.rivell.workbench.knowledge.UnauthorizedKnowledgeAccessException;
import com.rivell.workbench.retrieval.ClauseNotFoundException;
import com.rivell.workbench.retrieval.ClauseNotClassifiedException;
import com.rivell.workbench.retrieval.EnrichmentNotReadyException;
import com.rivell.workbench.retrieval.TraceNotFoundException;
import com.rivell.workbench.retrieval.UnauthorizedEvidenceAccessException;
import com.rivell.workbench.redline.ApprovedChangeSetNotFoundException;
import com.rivell.workbench.redline.RedlineArtifactStoreException;
import com.rivell.workbench.redline.RedlineArtifactUnavailableException;
import com.rivell.workbench.redline.RedlineExecutionFailedException;
import com.rivell.workbench.redline.RedlineExecutionNotFoundException;
import com.rivell.workbench.redline.RedlineNotAvailableException;
import com.rivell.workbench.redline.RedlineRetryNotAvailableException;
import com.rivell.workbench.redline.RedlineSourceUnavailableException;
import com.rivell.workbench.redline.UnauthorizedRedlineAccessException;
import com.rivell.workbench.review.FindingNotFoundException;
import com.rivell.workbench.review.ReviewEvidenceIntegrityException;
import com.rivell.workbench.review.InvalidReviewConfigurationException;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.UnauthorizedReviewAccessException;
import com.rivell.workbench.workflow.AnalysisNotCompleteException;
import com.rivell.workbench.workflow.DecisionConflictException;
import com.rivell.workbench.workflow.FindingNotActionableException;
import com.rivell.workbench.workflow.UntrackableChangeTargetException;
import com.rivell.workbench.workflow.InvalidDecisionException;
import com.rivell.workbench.workflow.ReviewAlreadyFinalizedException;
import com.rivell.workbench.workflow.ReviewNotFinalizableException;
import com.rivell.workbench.workflow.UnauthorizedWorkflowAccessException;
import com.rivell.workbench.foundation.ErrorResponse;

/**
 * Global API error mapping for all controllers. Domain exceptions map to explicit,
 * documented status codes; controllers never handle errors themselves.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final ErrorResponse PAYLOAD_TOO_LARGE = new ErrorResponse(
            "413", "Payload too large", "The uploaded file exceeds the maximum allowed size.");

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid request", e.getMessage());
    }

    @ExceptionHandler({ContractNotFoundException.class, ContractVersionNotFoundException.class,
            KnowledgeArtifactNotFoundException.class, KnowledgeCandidateNotFoundException.class,
            KnowledgeUnitNotFoundException.class, SemanticsNotFoundException.class,
            ClauseNotFoundException.class, TraceNotFoundException.class,
            ReviewNotFoundException.class, FindingNotFoundException.class,
            ApprovedChangeSetNotFoundException.class,
            NoSuchElementException.class})
    ResponseEntity<ErrorResponse> notFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, "404", "Not found",
                "The requested resource does not exist in this organization.");
    }

    @ExceptionHandler({UploadLimitExceededException.class,
            KnowledgeUploadLimitExceededException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ErrorResponse> uploadTooLarge(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler({UnauthorizedContractAccessException.class,
            UnauthorizedKnowledgeAccessException.class,
            UnauthorizedEvidenceAccessException.class,
            UnauthorizedReviewAccessException.class,
            UnauthorizedWorkflowAccessException.class})
    ResponseEntity<ErrorResponse> forbidden(RuntimeException e) {
        return error(HttpStatus.FORBIDDEN, "403", "Forbidden", "Not authorized for this operation.");
    }

    @ExceptionHandler(ReviewEvidenceIntegrityException.class)
    ResponseEntity<ErrorResponse> evidenceIntegrity(ReviewEvidenceIntegrityException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "500",
                "Finding evidence unavailable",
                "The finding evidence could not be resolved; internal diagnostics were recorded.");
    }

    @ExceptionHandler(InvalidDecisionException.class)
    ResponseEntity<ErrorResponse> invalidDecision(InvalidDecisionException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid decision", e.getMessage());
    }

    @ExceptionHandler({DecisionConflictException.class, FindingNotActionableException.class,
            AnalysisNotCompleteException.class, ReviewAlreadyFinalizedException.class,
            ReviewNotFinalizableException.class, UntrackableChangeTargetException.class})
    ResponseEntity<ErrorResponse> decisionConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Invalid state", e.getMessage());
    }

    @ExceptionHandler(InvalidReviewConfigurationException.class)
    ResponseEntity<ErrorResponse> invalidReviewConfiguration(
            InvalidReviewConfigurationException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid review configuration",
                e.getMessage());
    }

    @ExceptionHandler({InvalidCandidateReviewStateException.class, InvalidPublicationException.class,
            EnrichmentInProgressException.class, EnrichmentFailedException.class,
            EnrichmentNotReadyException.class, ClauseNotClassifiedException.class,
            com.rivell.workbench.knowledge.UnitNotMarkableException.class})
    ResponseEntity<ErrorResponse> invalidState(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Invalid state", e.getMessage());
    }

    @ExceptionHandler({InvalidCorrectionException.class,
            com.rivell.workbench.knowledge.InvalidConceptCorrectionException.class})
    ResponseEntity<ErrorResponse> invalidCorrection(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid correction", e.getMessage());
    }

    @ExceptionHandler(ContractNotReadableException.class)
    ResponseEntity<ErrorResponse> contractNotReadable(ContractNotReadableException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "422", "Contract not readable",
                e.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid request",
                "The request body could not be parsed.");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> storageUnavailable(IOException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "500", "Document storage unavailable",
                "The original document could not be stored or read.");
    }

    @ExceptionHandler({UnauthorizedRedlineAccessException.class})
    ResponseEntity<ErrorResponse> redlineForbidden(RuntimeException e) {
        return error(HttpStatus.FORBIDDEN, "403", "Forbidden",
                "Not authorized for this operation.");
    }

    @ExceptionHandler(RedlineExecutionNotFoundException.class)
    ResponseEntity<ErrorResponse> redlineNotFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, "404", "Not found",
                "No redline execution exists for this review.");
    }

    @ExceptionHandler({RedlineNotAvailableException.class,
            RedlineRetryNotAvailableException.class,
            RedlineArtifactUnavailableException.class,
            RedlineExecutionFailedException.class,
            RedlineSourceUnavailableException.class})
    ResponseEntity<ErrorResponse> redlineConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Invalid state", e.getMessage());
    }

    @ExceptionHandler(com.rivell.workbench.review.IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused(
            com.rivell.workbench.review.IdempotencyKeyReusedException e) {
        return error(HttpStatus.CONFLICT, "409", "Invalid state",
                "The supplied request id was already used for a different contract version or "
                        + "contract pack. Use a new request id, or repeat the original request "
                        + "unchanged.");
    }

    @ExceptionHandler(RedlineArtifactStoreException.class)
    ResponseEntity<ErrorResponse> redlineStoreUnavailable(RedlineArtifactStoreException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "503", "Document storage unavailable",
                "The verified redline artifact could not be stored. The review is unchanged and "
                        + "the execution can be retried.");
    }

    @ExceptionHandler(ConceptClassificationUnavailableException.class)
    ResponseEntity<ErrorResponse> semanticServiceUnavailable(RuntimeException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "503", "Semantic service unavailable",
                "The semantic classification is not available right now.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String title, String detail) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, title, detail));
    }
}
