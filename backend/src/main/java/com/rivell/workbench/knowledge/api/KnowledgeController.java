package com.rivell.workbench.knowledge.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rivell.workbench.foundation.ApiResult;
import com.rivell.workbench.foundation.ErrorResponse;
import com.rivell.workbench.foundation.Texts;
import com.rivell.workbench.knowledge.ArtifactManifest;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.ExtractionUnitConceptClassifier;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Pure HTTP surface for the knowledge module: delegates to services, maps domain read
 * models onto response DTOs, returns {@link ApiResult} envelopes. Error mapping is the
 * global handler's responsibility; binary originals bypass the JSON envelope.
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeQueryService queryService;
    private final CandidateReviewService reviewService;
    private final KnowledgePublicationService publicationService;
    private final ExtractionUnitConceptClassifier conceptClassifier;
    private final com.rivell.workbench.foundation.DocumentStorage documentStorage;

    KnowledgeController(
            KnowledgeIngestionService ingestionService,
            KnowledgeQueryService queryService,
            CandidateReviewService reviewService,
            KnowledgePublicationService publicationService,
            ExtractionUnitConceptClassifier conceptClassifier,
            com.rivell.workbench.foundation.DocumentStorage documentStorage) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.reviewService = reviewService;
        this.publicationService = publicationService;
        this.conceptClassifier = conceptClassifier;
        this.documentStorage = documentStorage;
    }

    @Operation(summary = "Upload a legal knowledge source with its human-declared manifest")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(
                    responseCode = "400", description = "Invalid upload or manifest",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "413", description = "Upload exceeds the configured size limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "415", description = "Unsupported knowledge source type",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<KnowledgeArtifactDetailResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("artifactType") ArtifactType artifactType,
            @RequestParam(required = false) String declaredContractTypes,
            @RequestParam(required = false) String contractPackKey,
            @RequestParam(required = false) String partyPerspective,
            @RequestParam(required = false) String paperSource,
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) LocalDate effectiveFrom,
            @RequestParam(required = false) LocalDate effectiveTo,
            @RequestParam(required = false) String note) throws IOException {
        try (var stream = file.getInputStream()) {
            var artifact = ingestionService.ingest(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    artifactType,
                    new ArtifactManifest(
                            declaredTypes(declaredContractTypes),
                            contractPackKey,
                            partyPerspective,
                            paperSource,
                            jurisdiction,
                            language,
                            effectiveFrom,
                            effectiveTo,
                            note),
                    stream);
            return ApiResult.ok(toDetail(artifact));
        }
    }

    @Operation(summary = "List the organization's knowledge artifacts")
    @GetMapping("/artifacts")
    ApiResult<List<KnowledgeArtifactSummaryResponse>> listArtifacts() {
        return ApiResult.ok(queryService.listArtifacts().stream()
                .map(KnowledgeController::toSummary)
                .toList());
    }

    @Operation(summary = "Get a knowledge artifact with its manifest and ingestion state")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/artifacts/{id}")
    ApiResult<KnowledgeArtifactDetailResponse> getArtifact(@PathVariable UUID id) {
        return ApiResult.ok(toDetail(queryService.getArtifact(id)));
    }

    @Operation(summary = "Get the deterministic structural parse of a knowledge source")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/artifacts/{id}/structure")
    ApiResult<List<KnowledgeStructureNodeView>> getStructure(@PathVariable UUID id) {
        return ApiResult.ok(queryService.getStructure(id).stream()
                .map(KnowledgeController::toNodeView)
                .toList());
    }

    @Operation(summary = "Get the extraction units of a knowledge artifact")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/artifacts/{id}/units")
    ApiResult<List<ExtractionUnitView>> getUnits(@PathVariable UUID id) {
        return ApiResult.ok(queryService.getUnits(id).stream()
                .map(unit -> new ExtractionUnitView(
                        unit.id(), unit.artifactId(), unit.ordinal(), unit.kind(),
                        unit.title(), unit.contentText(), unit.sourceNodeIds()))
                .toList());
    }

    @Operation(summary = "Get the compiled knowledge candidates of a knowledge artifact")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/artifacts/{id}/candidates")
    ApiResult<List<CandidateView>> getCandidates(@PathVariable UUID id) {
        return ApiResult.ok(queryService.getCandidates(id).stream()
                .map(KnowledgeController::toCandidateView)
                .toList());
    }

    @Operation(summary = "Get one knowledge candidate")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/candidates/{id}")
    ApiResult<CandidateView> getCandidate(@PathVariable UUID id) {
        return ApiResult.ok(toCandidateView(queryService.getCandidate(id)));
    }

    @Operation(summary = "Approve a candidate for publication")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid state for this operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/candidates/{id}/approve")
    ApiResult<CandidateView> approve(
            @PathVariable UUID id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResult.ok(toCandidateView(
                reviewService.approve(id, request == null ? null : request.note())));
    }

    @Operation(summary = "Edit a candidate's content or payload; the original is preserved")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid state for this operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/candidates/{id}/edit")
    ApiResult<CandidateView> edit(
            @PathVariable UUID id, @RequestBody ReviewEditRequest request) {
        return ApiResult.ok(toCandidateView(reviewService.edit(
                id, request.contentText(),
                request.payloadColumns() == null ? null : new com.rivell.workbench.knowledge.CandidatePayload(
                        request.payloadColumns()),
                request.note())));
    }

    @Operation(summary = "Reject a candidate")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid state for this operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/candidates/{id}/reject")
    ApiResult<CandidateView> reject(@PathVariable UUID id, @RequestBody(required = false) ReviewDecisionRequest request) {
        return ApiResult.ok(toCandidateView(
                reviewService.reject(id, request == null ? null : request.note())));
    }

    @Operation(summary = "Publish approved candidates as a new versioned knowledge snapshot")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid state for this operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/publications")
    ApiResult<PublicationView> publish(@RequestBody PublishRequest request) {
        var publication = publicationService.publish(request.candidateIds(), request.note());
        return ApiResult.ok(new PublicationView(
                publication.id(),
                publication.organizationId(),
                publication.publicationNumber(),
                publication.note(),
                publication.items().stream()
                        .map(item -> new PublicationView.ItemView(
                                item.id(), item.candidateId(),
                                item.supersededCandidateId(), item.action()))
                        .toList(),
                publication.createdAt()));
    }

    @Operation(summary = "List the organization's published knowledge versions")
    @GetMapping("/publications")
    ApiResult<List<PublicationView>> listPublications() {
        return ApiResult.ok(queryService.listPublications().stream()
                .map(KnowledgeController::toPublicationView)
                .toList());
    }

    @Operation(summary = "Get one published knowledge version")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Resource does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/publications/{id}")
    ApiResult<PublicationView> getPublication(@PathVariable UUID id) {
        return ApiResult.ok(toPublicationView(queryService.getPublication(id)));
    }

    @Operation(summary = "Propose the bounded legal concepts an extraction unit expresses")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "The unit has no text to classify",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unit does not exist in this organization",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Semantic classification is not available",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/artifacts/{artifactId}/units/{unitId}/classification")
    ApiResult<UnitConceptClassificationView> classifyUnit(
            @PathVariable UUID artifactId, @PathVariable UUID unitId) {
        return ApiResult.ok(UnitConceptClassificationView.from(conceptClassifier.classify(artifactId, unitId)));
    }

    @Operation(summary = "Download the original uploaded knowledge source")
    @ApiResponse(
            responseCode = "200", description = "Original document bytes",
            headers = @Header(name = "Content-Disposition",
                    description = "attachment with the sanitized original filename (RFC 6266/5987)"))
    @ApiResponse(
            responseCode = "404", description = "Artifact does not exist in this organization",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/artifacts/{id}/original")
    ResponseEntity<byte[]> downloadOriginal(@PathVariable UUID id) throws IOException {
        var source = queryService.getSource(id);
        var bytes = storageBytes(source.storageKey());
        var disposition = org.springframework.http.ContentDisposition.attachment()
                .filename(source.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(source.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(bytes.length)
                .body(bytes);
    }

    private byte[] storageBytes(String storageKey) throws IOException {
        try (var stream = documentStorage.open(new com.rivell.workbench.foundation.StorageKey(storageKey))) {
            return stream.readAllBytes();
        }
    }

    private static Set<String> declaredTypes(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(Texts::normalize)
                .filter(value -> value != null)
                .collect(Collectors.toSet());
    }

    private static KnowledgeArtifactSummaryResponse toSummary(KnowledgeArtifact artifact) {
        return new KnowledgeArtifactSummaryResponse(
                artifact.id(), artifact.artifactType(), artifact.filename(),
                artifact.contentType(), artifact.sizeBytes(), artifact.sha256(),
                artifact.ingestStatus(), artifact.createdAt());
    }

    private static KnowledgeArtifactDetailResponse toDetail(KnowledgeArtifact artifact) {
        return new KnowledgeArtifactDetailResponse(
                artifact.id(), artifact.artifactType(), artifact.filename(),
                artifact.contentType(), artifact.sizeBytes(), artifact.sha256(),
                artifact.storageKey(),
                artifact.manifest().declaredContractTypes(),
                artifact.manifest().contractPackKey(),
                artifact.manifest().partyPerspective(),
                artifact.manifest().paperSource(),
                artifact.manifest().jurisdiction(),
                artifact.manifest().language(),
                artifact.manifest().effectiveFrom(),
                artifact.manifest().effectiveTo(),
                artifact.manifest().note(),
                artifact.ingestStatus(), artifact.parserVersion(), artifact.parseWarnings(),
                artifact.createdAt());
    }

    private static CandidateView toCandidateView(com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return new CandidateView(
                candidate.id(), candidate.artifactId(), candidate.extractionUnitId(),
                candidate.candidateType(), candidate.reviewStatus(), candidate.rawLabel(),
                candidate.contentText(), candidate.payload().columns(),
                candidate.legalConceptKey(), candidate.editedContentText(),
                candidate.editedPayload() == null ? null : candidate.editedPayload().columns(),
                candidate.supersedingCandidateId(),
                candidate.scope().contractPackKey(),
                candidate.scope().declaredContractTypes(),
                candidate.scope().partyPerspective(),
                candidate.scope().paperSource(),
                candidate.scope().jurisdiction(),
                candidate.scope().effectiveFrom(),
                candidate.scope().effectiveTo(),
                candidate.conflictSignals().stream()
                        .map(signal -> new CandidateView.ConflictSignalView(
                                signal.conflictType(), signal.message(),
                                signal.publishedCandidateId()))
                        .toList(),
                candidate.reviewNote(), candidate.reviewedAt(),
                candidate.createdAt(), candidate.updatedAt());
    }

    private static KnowledgeStructureNodeView toNodeView(com.rivell.workbench.knowledge.KnowledgeSourceNode node) {
        return new KnowledgeStructureNodeView(
                node.id(), node.artifactId(), node.parentId(), node.nodeType(), node.ordinal(),
                node.text(), node.headingLevel(), node.numberingLabel(),
                node.partPath(), node.bodyIndex(), node.paragraphIndex(), node.tableIndex(),
                node.rowIndex(), node.columnIndex(), node.runStart(), node.runEnd(),
                node.sheetName(),
                node.mergedRegion() == null ? null : new KnowledgeStructureNodeView.MergedRegionView(
                        node.mergedRegion().firstRow(), node.mergedRegion().lastRow(),
                        node.mergedRegion().firstColumn(), node.mergedRegion().lastColumn()),
                node.rawStyleId());
    }

    private static PublicationView toPublicationView(com.rivell.workbench.knowledge.KnowledgePublication publication) {
        return new PublicationView(
                publication.id(), publication.organizationId(), publication.publicationNumber(),
                publication.note(),
                publication.items().stream()
                        .map(item -> new PublicationView.ItemView(
                                item.id(), item.candidateId(),
                                item.supersededCandidateId(), item.action()))
                        .toList(),
                publication.createdAt());
    }
}
