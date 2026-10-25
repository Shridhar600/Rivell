package com.rivell.workbench.platform.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.rivell.workbench.foundation.DocumentWorkBusyException;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineSourceUnavailableException;

/**
 * DW-094 and DW-095: two error paths that used to leak or misdescribe the failure. A 400 carries
 * a code-owned sentence, never the exception's own message; a saturated document-work limit
 * answers 503 with a retry hint instead of holding the request.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aBadRequestNeverEchoesTheExceptionMessage() {
        var response = handler.badRequest(new IllegalArgumentException(
                "Unknown field at com.rivell.workbench.Secret:42 (org 7f3c)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().detail())
                .doesNotContain("com.rivell")
                .doesNotContain("Secret")
                .doesNotContain("7f3c")
                .isNotBlank();
    }

    @Test
    void aBusyDocumentLimitAnswers503WithARetryHint() {
        var response = handler.documentWorkBusy(new DocumentWorkBusyException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().title()).isEqualTo("Service busy");
        assertThat(response.getBody().detail())
                .doesNotContain("DocumentWork")
                .doesNotContain("Semaphore")
                .matches(".*(?i)try again.*");
    }

    @Test
    void aSourceUnavailableConflictCarriesItsTypedCode() {
        var response = handler.redlineSourceUnavailable(new RedlineSourceUnavailableException(
                RedlineFailureCode.SOURCE_INTEGRITY_MISMATCH, "Corrupt source package"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().failureCode()).isEqualTo("SOURCE_INTEGRITY_MISMATCH");
    }
}
