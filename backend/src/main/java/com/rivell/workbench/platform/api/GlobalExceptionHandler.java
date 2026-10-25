package com.rivell.workbench.platform.api;

import java.io.IOException;
import java.util.NoSuchElementException;

import org.springframework.http.HttpHeaders;
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
import com.rivell.workbench.foundation.DocumentWorkBusyException;
import com.rivell.workbench.foundation.ErrorResponse;
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

/**
 * Global API error mapping for all controllers. Domain exceptions map to explicit,
 * documented status codes; controllers never handle errors themselves.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final ErrorResponse PAYLOAD_TOO_LARGE = new ErrorResponse(
            "413", "File too large", "This file is larger than Rivell accepts. Upload a smaller file.");

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        // The message is the exception's own, and several of them name identifiers, classes or
        // internal locations. The client gets a code-owned sentence; the type is all it needs (DW-094).
        return error(HttpStatus.BAD_REQUEST, "400", "Request not accepted",
                "Rivell could not accept this request. Check the values and try again.");
    }

    @ExceptionHandler(DocumentWorkBusyException.class)
    ResponseEntity<ErrorResponse> documentWorkBusy(DocumentWorkBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER,
                        String.valueOf(DocumentWorkBusyException.RETRY_AFTER_SECONDS))
                .body(new ErrorResponse("503", "Service busy",
                        "Rivell is handling as many files as it can right now. Nothing was changed; "
                                + "try again in a moment."));
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
                "Rivell could not find that item. It may have been removed.");
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
        return error(HttpStatus.FORBIDDEN, "403", "Not allowed",
                "You do not have permission to do this.");
    }

    @ExceptionHandler(ReviewEvidenceIntegrityException.class)
    ResponseEntity<ErrorResponse> evidenceIntegrity(ReviewEvidenceIntegrityException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "500",
                "Finding evidence unavailable",
                "Rivell could not load the evidence for this finding. Try again. If it keeps "
                        + "happening, contact your administrator.");
    }

    @ExceptionHandler(InvalidDecisionException.class)
    ResponseEntity<ErrorResponse> invalidDecision(InvalidDecisionException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Decision not accepted", e.getMessage());
    }

    @ExceptionHandler({DecisionConflictException.class, FindingNotActionableException.class,
            AnalysisNotCompleteException.class, ReviewAlreadyFinalizedException.class,
            ReviewNotFinalizableException.class, UntrackableChangeTargetException.class})
    ResponseEntity<ErrorResponse> decisionConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Action not available", e.getMessage());
    }

    @ExceptionHandler(InvalidReviewConfigurationException.class)
    ResponseEntity<ErrorResponse> invalidReviewConfiguration(
            InvalidReviewConfigurationException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Review setup not accepted",
                e.getMessage());
    }

    @ExceptionHandler({InvalidCandidateReviewStateException.class, InvalidPublicationException.class,
            EnrichmentInProgressException.class, EnrichmentFailedException.class,
            EnrichmentNotReadyException.class, ClauseNotClassifiedException.class,
            com.rivell.workbench.knowledge.UnitNotMarkableException.class})
    ResponseEntity<ErrorResponse> invalidState(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Action not available", e.getMessage());
    }

    @ExceptionHandler({InvalidCorrectionException.class,
            com.rivell.workbench.knowledge.InvalidConceptCorrectionException.class})
    ResponseEntity<ErrorResponse> invalidCorrection(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Correction not accepted", e.getMessage());
    }

    @ExceptionHandler(ContractNotReadableException.class)
    ResponseEntity<ErrorResponse> contractNotReadable(ContractNotReadableException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "422", "Contract not readable",
                e.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Request not accepted",
                "Rivell could not read this request.");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> storageUnavailable(IOException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "500", "Document storage unavailable",
                "Rivell could not save or open the original file. Try again.");
    }

    @ExceptionHandler({UnauthorizedRedlineAccessException.class})
    ResponseEntity<ErrorResponse> redlineForbidden(RuntimeException e) {
        return error(HttpStatus.FORBIDDEN, "403", "Not allowed",
                "You do not have permission to do this.");
    }

    @ExceptionHandler(RedlineExecutionNotFoundException.class)
    ResponseEntity<ErrorResponse> redlineNotFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, "404", "Not found",
                "No redline has been generated for this review yet.");
    }

    @ExceptionHandler(RedlineSourceUnavailableException.class)
    ResponseEntity<ErrorResponse> redlineSourceUnavailable(RedlineSourceUnavailableException e) {
        // The source failure is typed: the client maps it to plain copy without reading the message.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("409", "Action not available", e.getMessage(),
                        e.code().name()));
    }

    @ExceptionHandler({RedlineNotAvailableException.class,
            RedlineRetryNotAvailableException.class,
            RedlineArtifactUnavailableException.class,
            RedlineExecutionFailedException.class})
    ResponseEntity<ErrorResponse> redlineConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Action not available", e.getMessage());
    }

    @ExceptionHandler(com.rivell.workbench.review.IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused(
            com.rivell.workbench.review.IdempotencyKeyReusedException e) {
        return error(HttpStatus.CONFLICT, "409", "Action not available",
                "This review request was already used for a different contract version or "
                        + "review pack. Start a new review, or repeat the original request "
                        + "unchanged.");
    }

    @ExceptionHandler(RedlineArtifactStoreException.class)
    ResponseEntity<ErrorResponse> redlineStoreUnavailable(RedlineArtifactStoreException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "503", "Document storage unavailable",
                "Rivell could not save the redline file. Your review is unchanged. Generate the "
                        + "redline again.");
    }

    @ExceptionHandler(ConceptClassificationUnavailableException.class)
    ResponseEntity<ErrorResponse> semanticServiceUnavailable(RuntimeException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "503", "Service unavailable",
                "Rivell cannot sort entries by topic right now. Try again later.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String title, String detail) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, title, detail));
    }
}
