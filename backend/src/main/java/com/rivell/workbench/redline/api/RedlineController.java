package com.rivell.workbench.redline.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rivell.workbench.foundation.ApiResult;
import com.rivell.workbench.foundation.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Pure HTTP surface for deterministic redline execution: delegates to
 * {@link RedlineApiService}. Error mapping is the global handler's responsibility.
 */
@RestController
@RequestMapping("/api")
class RedlineController {

    static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final RedlineApiService apiService;

    RedlineController(RedlineApiService apiService) {
        this.apiService = apiService;
    }

    @Operation(summary = "Execute the frozen approved change set as a tracked-change DOCX")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Missing reviews:redline",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not finalized, or the execution "
                    + "failed deterministically (full report via GET)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/reviews/{id}/redline")
    ApiResult<RedlineExecutionView> trigger(@PathVariable UUID id) {
        return ApiResult.ok(apiService.trigger(id));
    }

    @Operation(summary = "Read the redline execution state and per-change report")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404",
            description = "No execution exists for this review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/redline")
    ApiResult<RedlineExecutionView> find(@PathVariable UUID id) {
        return ApiResult.ok(apiService.find(id));
    }

    @Operation(summary = "Download the verified tracked-change DOCX")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified redline bytes",
                    headers = @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Content-Disposition",
                            description = "attachment with the deterministic redline filename"),
                    content = @Content(mediaType = DOCX_MEDIA_TYPE)),
            @ApiResponse(responseCode = "404", description = "No execution exists for this review",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "No verified artifact is available",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/reviews/{id}/redline/download")
    ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var download = apiService.download(id);
        var disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(download.content().length)
                .body(download.content());
    }
}
