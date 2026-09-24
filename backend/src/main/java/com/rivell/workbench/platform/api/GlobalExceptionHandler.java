package com.rivell.workbench.platform.api;

import java.io.IOException;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.rivell.workbench.contracts.ContractNotFoundException;
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
            UnauthorizedEvidenceAccessException.class})
    ResponseEntity<ErrorResponse> forbidden(RuntimeException e) {
        return error(HttpStatus.FORBIDDEN, "403", "Forbidden", "Not authorized for this operation.");
    }

    @ExceptionHandler({InvalidCandidateReviewStateException.class, InvalidPublicationException.class,
            EnrichmentInProgressException.class, EnrichmentFailedException.class,
            EnrichmentNotReadyException.class, ClauseNotClassifiedException.class})
    ResponseEntity<ErrorResponse> invalidState(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "409", "Invalid state", e.getMessage());
    }

    @ExceptionHandler(InvalidCorrectionException.class)
    ResponseEntity<ErrorResponse> invalidCorrection(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid correction", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> storageUnavailable(IOException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "500", "Document storage unavailable",
                "The original document could not be stored or read.");
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
