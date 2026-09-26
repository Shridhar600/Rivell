package com.rivell.workbench.workflow.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * Pure HTTP surface for the human review workflow: delegates to
 * {@link WorkflowApiService} and returns the uniform {@link ApiResult} envelope. Error
 * mapping is the global handler's responsibility.
 */
@RestController
@RequestMapping("/api")
class WorkflowController {

    private final WorkflowApiService apiService;
    private final ReviewDiagnosticsService diagnosticsService;

    WorkflowController(WorkflowApiService apiService,
            ReviewDiagnosticsService diagnosticsService) {
        this.apiService = apiService;
        this.diagnosticsService = diagnosticsService;
    }

    @Operation(summary = "Write or revise the current human decision for a finding")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid decision",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing reviews:decide",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown review or finding",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Stale version, not actionable, "
                    + "analysis incomplete, or already finalized",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PutMapping("/reviews/{id}/findings/{findingId}/decision")
    ApiResult<DecisionView> decide(
            @PathVariable UUID id,
            @PathVariable UUID findingId,
            @RequestBody DecisionRequest request) {
        return ApiResult.ok(apiService.decide(id, findingId, request));
    }

    @Operation(summary = "List the current effective decisions of a review's active run")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/decisions")
    ApiResult<DecisionListResponse> currentDecisions(
            @PathVariable UUID id, @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(apiService.currentDecisions(id, limit));
    }

    @Operation(summary = "List one finding's decision revisions, newest first")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review or finding",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/findings/{findingId}/decisions")
    ApiResult<DecisionListResponse> history(
            @PathVariable UUID id, @PathVariable UUID findingId,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(apiService.history(id, findingId, limit));
    }

    @Operation(summary = "Get the derived human-review state, counts, and readiness")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/human-review")
    ApiResult<HumanReviewView> humanReview(@PathVariable UUID id) {
        return ApiResult.ok(apiService.humanReview(id));
    }

    @Operation(summary = "Developer diagnostics for one review: run state, check outcomes, counts, decision outcomes, finalization, redline, and anything stalling progress")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({@ApiResponse(responseCode = "403", description = "Missing reviews:read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/reviews/{id}/diagnostics")
    ApiResult<ReviewDiagnosticsView> diagnostics(@PathVariable UUID id) {
        return ApiResult.ok(diagnosticsService.forReview(id));
    }

    @Operation(summary = "Finalize the review and freeze the approved change set for redline")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Missing reviews:decide",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown review",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The review is not finalizable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/reviews/{id}/finalize")
    ApiResult<ApprovedChangeSetView> finalizeReview(@PathVariable UUID id) {
        return ApiResult.ok(apiService.finalizeReview(id));
    }

    @Operation(summary = "Get the frozen approved change set of a finalized review")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Unknown review or not finalized",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/reviews/{id}/approved-change-set")
    ApiResult<ApprovedChangeSetView> approvedChangeSet(@PathVariable UUID id) {
        return ApiResult.ok(apiService.approvedChangeSet(id));
    }
}
