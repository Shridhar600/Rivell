package com.rivell.workbench.retrieval.api;

import java.util.UUID;

import com.rivell.workbench.foundation.ApiResult;
import com.rivell.workbench.retrieval.RetrievalTrace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pure HTTP surface for authority-aware evidence construction (M6 developer
 * surface until M7 provides the review consumer).
 */
@RestController
@RequestMapping("/api/retrieval")
@Tag(name = "Retrieval", description = "Authority-aware evidence construction (M6)")
class RetrievalController {

    private final EvidenceApiService apiService;
    private final TraceApiService traceApiService;

    RetrievalController(EvidenceApiService apiService, TraceApiService traceApiService) {
        this.apiService = apiService;
        this.traceApiService = traceApiService;
    }

    @Operation(summary = "Build one evidence bundle for a reviewed clause")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bundle built"),
            @ApiResponse(responseCode = "400", description = "Malformed request",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Unknown contract or clause",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "409", description = "Enrichment not READY",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))})
    @PostMapping("/evidence")
    @ResponseStatus(HttpStatus.OK)
    ApiResult<RetrievalResponses.EvidenceBundleResponse> build(
            @RequestBody RetrievalRequests.EvidenceBuildRequest request) {
        return ApiResult.ok(apiService.build(request));
    }

    @Operation(summary = "Inspect one persisted retrieval trace")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trace detail"),
            @ApiResponse(responseCode = "404", description = "Unknown trace",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))})
    @GetMapping("/traces/{id}")
    @ResponseStatus(HttpStatus.OK)
    ApiResult<RetrievalResponses.RetrievalTraceResponse> trace(@PathVariable UUID id) {
        return ApiResult.ok(traceApiService.detail(id));
    }
}
