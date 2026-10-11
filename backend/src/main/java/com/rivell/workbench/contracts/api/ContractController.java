package com.rivell.workbench.contracts.api;

import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.ContractDownloadService;
import com.rivell.workbench.foundation.ApiResult;
import com.rivell.workbench.foundation.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Pure HTTP surface for contracts: delegates to application services, maps domain
 * read models onto response DTOs, and returns the uniform {@link ApiResponse} envelope.
 * Business logic and error mapping live in services and the global handler.
 */
@RestController
@RequestMapping("/api/contracts")
class ContractController {

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ContractIngestionService ingestionService;
    private final ContractQueryService queryService;
    private final ContractDownloadService downloadService;

    ContractController(
            ContractIngestionService ingestionService,
            ContractQueryService queryService,
            ContractDownloadService downloadService) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.downloadService = downloadService;
    }

    @Operation(summary = "Upload and parse a DOCX contract")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid upload",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "Upload exceeds the configured size limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<IngestResponse> upload(@RequestPart("file") MultipartFile file) throws IOException {
        try (var stream = file.getInputStream()) {
            var result = ingestionService.ingest(
                    file.getOriginalFilename(), file.getContentType(), stream);
            return ApiResult.ok(new IngestResponse(
                    result.contractId(),
                    result.contractVersionId(),
                    result.versionNumber(),
                    result.parseStatus(),
                    result.parseWarnings(),
                    result.filename(),
                    result.sha256(),
                    result.nodeCount()));
        }
    }

    @Operation(summary = "List the organization's contracts newest first (bounded). "
            + "Supply nextCursor to continue; a new upload always creates a new contract, "
            + "so this endpoint never lists versions of an existing contract.")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Missing contracts:read",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    ApiResult<ContractListResponse> listContracts(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        var page = queryService.listContractsPage(limit, cursor);
        return ApiResult.ok(new ContractListResponse(
                page.items().stream()
                        .map(entry -> new ContractListItemResponse(
                                entry.contractId(),
                                entry.title(),
                                entry.createdAt(),
                                new ContractSummaryResponse.LatestVersion(
                                        entry.latestVersion().versionNumber(),
                                        entry.latestVersion().parseStatus(),
                                        entry.latestVersion().parseWarnings(),
                                        entry.latestVersion().parserVersion(),
                                        entry.latestVersion().sha256(),
                                        entry.latestVersion().filename(),
                                        entry.latestVersion().nodeCount())))
                        .toList(),
                page.nextCursor()));
    }

    @Operation(summary = "Get a contract with its latest version state")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contract does not exist in this organization",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    ApiResult<ContractSummaryResponse> getContract(@PathVariable UUID id) {
        var summary = queryService.getContractSummary(id);
        var version = summary.latestVersion();
        return ApiResult.ok(new ContractSummaryResponse(
                summary.contract().id(),
                summary.contract().title(),
                new ContractSummaryResponse.LatestVersion(
                        version.versionNumber(),
                        version.parseStatus(),
                        version.parseWarnings(),
                        version.parserVersion(),
                        version.sha256(),
                        version.filename(),
                        version.nodeCount())));
    }

    @Operation(summary = "Get the canonical structure of a contract version")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contract or version does not exist in this organization",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/versions/{versionNumber}/structure")
    ApiResult<ContractStructureResponse> getStructure(
            @PathVariable UUID id, @PathVariable int versionNumber) {
        var structure = queryService.getStructure(id, versionNumber);
        return ApiResult.ok(new ContractStructureResponse(
                structure.contractId(),
                structure.versionNumber(),
                structure.parserVersion(),
                structure.parseStatus(),
                structure.parseWarnings(),
                structure.nodeCount(),
                structure.nodes().stream()
                        .map(node -> new ContractStructureResponse.NodeView(
                                node.id(),
                                node.parentId(),
                                node.nodeType(),
                                node.ordinal(),
                                node.text(),
                                node.headingLevel(),
                                node.numberingLabel(),
                                new ContractStructureResponse.SourceSpanView(
                                        node.sourceSpan().partPath(),
                                        node.sourceSpan().bodyIndex(),
                                        node.sourceSpan().paragraphIndex(),
                                        node.sourceSpan().tableIndex(),
                                        node.sourceSpan().rowIndex(),
                                        node.sourceSpan().cellIndex(),
                                        node.sourceSpan().runStart(),
                                        node.sourceSpan().runEnd()),
                                node.rawStyleId()))
                        .toList()));
    }

    @Operation(summary = "Report how much of the uploaded file's text the parsed structure holds")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contract or version does not exist in this organization",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/versions/{versionNumber}/coverage")
    ApiResult<com.rivell.workbench.contracts.ParseCoverageView> getCoverage(
            @PathVariable UUID id, @PathVariable int versionNumber) {
        return ApiResult.ok(com.rivell.workbench.contracts.ParseCoverageView.of(
                queryService.getParseCoverage(id, versionNumber)));
    }

    @Operation(summary = "Download the original uploaded DOCX of a contract version")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Original document bytes",
                    headers = @Header(name = "Content-Disposition",
                            description = "attachment with the sanitized original filename (RFC 6266/5987)"),
                    content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            @ApiResponse(responseCode = "404", description = "Contract or version does not exist in this organization",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/versions/{versionNumber}/original")
    ResponseEntity<byte[]> downloadOriginal(
            @PathVariable UUID id, @PathVariable int versionNumber) throws IOException {
        var download = downloadService.open(id, versionNumber);
        var disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(download.content().length)
                .body(download.content());
    }
}
