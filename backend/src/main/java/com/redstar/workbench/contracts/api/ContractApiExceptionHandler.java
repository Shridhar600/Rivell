package com.redstar.workbench.contracts.api;

import java.io.IOException;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.redstar.workbench.contracts.UploadLimitExceededException;
import com.redstar.workbench.contracts.UnauthorizedContractAccessException;

@RestControllerAdvice(assignableTypes = ContractController.class)
class ContractApiExceptionHandler {

    private static final ErrorResponse PAYLOAD_TOO_LARGE = new ErrorResponse(
            "413", "Payload too large", "The uploaded file exceeds the maximum allowed size.");

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "400", "Invalid request", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorResponse> notFound(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, "404", "Not found", "The requested resource does not exist.");
    }

    @ExceptionHandler({UploadLimitExceededException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ErrorResponse> uploadTooLarge(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(UnauthorizedContractAccessException.class)
    ResponseEntity<ErrorResponse> forbidden(UnauthorizedContractAccessException e) {
        return error(HttpStatus.FORBIDDEN, "403", "Forbidden", "Not authorized for this operation.");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> storageUnavailable(IOException e) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR, "500",
                "Document storage unavailable",
                "The original document could not be stored or read.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String title, String detail) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, title, detail));
    }
}
