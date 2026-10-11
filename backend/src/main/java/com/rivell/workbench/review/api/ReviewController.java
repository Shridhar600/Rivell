package com.rivell.workbench.review.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rivell.workbench.foundation.ApiResult;
import com.rivell.workbench.foundation.ErrorResponse;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingSeverity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Pure HTTP surface for reviews: delegates to {@link ReviewApiService} and returns the
 * uniform {@link ApiResult} envelope. Error mapping is the global handler's
 * responsibility.
 */
@RestController
@RequestMapping("/api")
class ReviewController {

    private final ReviewApiService apiService;

    ReviewController(ReviewApiService apiService) {
        this.apiService = apiService;
    }

    @Operation(summary = "Create a review for a contract version (optional explicit pack)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid review configuration",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown contract version",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Contract version is not enrichment-ready",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/contracts/{contractId}/versions/{versionNumber}/reviews")
    ApiResult<ReviewSummaryView> create(
            @PathVariable UUID contractId,
            @PathVariable int versionNumber,
            @RequestBody(required = false) CreateReviewRequest request) {
        return ApiResult.ok(apiService.create(contractId, versionNumber,
                request == null ? null : request.contractPackKey(),
                request == null ? null : request.clientRequestId()));
    }

    @Operation(summary = "List the currently selectable contract packs")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "403", description = "Missing reviews:read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/packs")
    ApiResult<ReviewPackCatalogView> packs() {
        return ApiResult.ok(apiService.packs());
    }

    @Operation(summary = "List reviews newest first (bounded), optionally scoped to one "
            + "contract. Omitting contractId returns the whole organization; supply the "
            + "nextCursor value to continue.")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({@ApiResponse(responseCode = "404", description = "Unknown contract",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/reviews")
    ApiResult<ReviewListResponse> list(
            @RequestParam(required = false) UUID contractId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResult.ok(apiService.list(contractId, limit, cursor));
    }

    @Operation(summary = "Get a review summary with its active analysis run and counts")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}")
    ApiResult<ReviewSummaryView> summary(@PathVariable UUID id) {
        return ApiResult.ok(apiService.summary(id));
    }

    @Operation(summary = "Get typed analysis progress for a review")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/progress")
    ApiResult<ReviewProgressView> progress(@PathVariable UUID id) {
        return ApiResult.ok(apiService.progress(id));
    }

    @Operation(summary = "List the active run's findings with optional filters (bounded)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/findings")
    ApiResult<FindingListResponse> findings(
            @PathVariable UUID id,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) FindingClassification classification,
            @RequestParam(required = false) FindingSeverity severity,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(apiService.findings(id, concept, classification, severity, limit));
    }

    @Operation(summary = "Get one finding's detail with claims, evidence, and absence basis")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown finding",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/findings/{findingId}")
    ApiResult<FindingDetailView> finding(
            @PathVariable UUID id, @PathVariable UUID findingId) {
        return ApiResult.ok(apiService.finding(id, findingId));
    }
}
