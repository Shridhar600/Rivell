package com.redstar.workbench.contracts.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.redstar.workbench.contracts.ContractIngestionResult;
import com.redstar.workbench.contracts.ContractIngestionService;
import com.redstar.workbench.contracts.ContractDownloadService;
import com.redstar.workbench.contracts.ContractQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

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
    @ApiResponse(responseCode = "200", description = "Processed; inspect parseStatus for the outcome",
            content = @Content(schema = @Schema(implementation = IngestResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid upload",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "Upload exceeds the configured size limit",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    IngestResponse upload(@RequestPart("file") MultipartFile file) throws IOException {
        try (var stream = file.getInputStream()) {
            ContractIngestionResult result = ingestionService.ingest(
                    file.getOriginalFilename(), file.getContentType(), stream);
            return new IngestResponse(
                    result.contractId(),
                    result.contractVersionId(),
                    result.versionNumber(),
                    result.parseStatus(),
                    result.parseWarnings(),
                    result.filename(),
                    result.sha256(),
                    result.nodeCount());
        }
    }

    @Operation(summary = "Get a contract with its latest version state")
    @ApiResponse(responseCode = "200", description = "Contract found",
            content = @Content(schema = @Schema(implementation = ContractSummaryResponse.class)))
    @ApiResponse(responseCode = "404", description = "Contract does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{id}")
    ContractSummaryResponse getContract(@PathVariable UUID id) {
        var contract = queryService.getContract(id);
        var version = queryService.getLatestVersionSummary(id);
        return new ContractSummaryResponse(
                contract.id(),
                contract.title(),
                new ContractSummaryResponse.LatestVersion(
                        version.versionNumber(),
                        version.parseStatus(),
                        version.parseWarnings(),
                        version.parserVersion(),
                        version.sha256(),
                        version.filename(),
                        version.nodeCount()));
    }

    @Operation(summary = "Get the canonical structure of a contract version")
    @ApiResponse(responseCode = "200", description = "Structure found",
            content = @Content(schema = @Schema(implementation = ContractStructureResponse.class)))
    @ApiResponse(responseCode = "404", description = "Contract or version does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{id}/versions/{versionNumber}/structure")
    ContractStructureResponse getStructure(
            @PathVariable UUID id, @PathVariable int versionNumber) {
        var structure = queryService.getStructure(id, versionNumber);
        return new ContractStructureResponse(
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
                        .toList());
    }

    @Operation(summary = "Download the original uploaded DOCX of a contract version")
    @ApiResponse(responseCode = "200", description = "Original document bytes",
            headers = @Header(name = "Content-Disposition",
                    description = "attachment with the sanitized original filename (RFC 6266/5987)"),
            content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    @ApiResponse(responseCode = "404", description = "Contract or version does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
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
