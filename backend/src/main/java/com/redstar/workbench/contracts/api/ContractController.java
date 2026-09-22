package com.redstar.workbench.contracts.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.redstar.workbench.contracts.ContractIngestionResult;
import com.redstar.workbench.contracts.ContractIngestionService;
import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.foundation.StorageKey;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ContractIngestionService ingestionService;
    private final DocumentStorage documentStorage;
    private final JdbcClient jdbcClient;

    public ContractController(
            ContractIngestionService ingestionService,
            DocumentStorage documentStorage,
            JdbcClient jdbcClient) {
        this.ingestionService = ingestionService;
        this.documentStorage = documentStorage;
        this.jdbcClient = jdbcClient;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse upload(@RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream stream = file.getInputStream()) {
            ContractIngestionResult result = ingestionService.ingest(
                    file.getOriginalFilename(), file.getContentType(), stream);
            return new IngestResponse(
                    result.contractId(),
                    result.contractVersionId(),
                    result.versionNumber(),
                    result.parseStatus().name(),
                    result.sha256(),
                    result.nodeCount());
        }
    }

    @GetMapping("/{id}")
    public ContractSummaryResponse getContract(@PathVariable UUID id) {
        var contract = jdbcClient.sql("""
                select id, title from contract where id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new Object[] {
                        UUID.fromString(rs.getString("id")),
                        rs.getString("title")})
                .optional()
                .orElseThrow(() -> new NoSuchElementException("Contract not found: " + id));

        var version = jdbcClient.sql("""
                select cv.version_number, cv.parse_status, sa.sha256,
                       (select count(*) from document_node n where n.contract_version_id = cv.id) as node_count
                from contract_version cv
                join source_artifact sa on sa.id = cv.source_artifact_id
                where cv.contract_id = :id
                order by cv.version_number desc
                limit 1
                """)
                .param("id", id)
                .query((rs, rowNum) -> new ContractSummaryResponse.LatestVersion(
                        rs.getInt("version_number"),
                        rs.getString("parse_status"),
                        rs.getString("sha256"),
                        rs.getInt("node_count")))
                .single();

        return new ContractSummaryResponse((UUID) contract[0], (String) contract[1], version);
    }

    @GetMapping("/{id}/versions/{versionNumber}/structure")
    public ContractStructureResponse getStructure(
            @PathVariable UUID id, @PathVariable int versionNumber) {
        var version = jdbcClient.sql("""
                select id, parser_version, parse_status
                from contract_version
                where contract_id = :id and version_number = :versionNumber
                """)
                .param("id", id)
                .param("versionNumber", versionNumber)
                .query((rs, rowNum) -> new VersionMeta(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("parser_version"),
                        rs.getString("parse_status")))
                .optional()
                .orElseThrow(() -> new NoSuchElementException(
                        "Contract version not found: " + id + "/" + versionNumber));

        var nodes = jdbcClient.sql("""
                with recursive tree as (
                    select n.*, array[n.ordinal] as sort_path
                    from document_node n
                    where n.contract_version_id = :versionId and n.parent_id is null
                    union all
                    select n.*, t.sort_path || n.ordinal
                    from document_node n
                    join tree t on n.parent_id = t.id
                )
                select id, parent_id, node_type, ordinal, text, heading_level,
                       part_path, body_index, paragraph_index, table_index, row_index, cell_index,
                       run_start, run_end, raw_style_id
                from tree
                order by sort_path
                """)
                .param("versionId", version.id())
                .query((rs, rowNum) -> new ContractStructureResponse.NodeView(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("parent_id") == null
                                ? null
                                : UUID.fromString(rs.getString("parent_id")),
                        rs.getString("node_type"),
                        rs.getInt("ordinal"),
                        rs.getString("text"),
                        rs.getObject("heading_level") == null ? null : rs.getInt("heading_level"),
                        new ContractStructureResponse.SourceSpanView(
                                rs.getString("part_path"),
                                rs.getInt("body_index"),
                                getNullableInt(rs, "paragraph_index"),
                                getNullableInt(rs, "table_index"),
                                getNullableInt(rs, "row_index"),
                                getNullableInt(rs, "cell_index"),
                                getNullableInt(rs, "run_start"),
                                getNullableInt(rs, "run_end")),
                        rs.getString("raw_style_id")))
                .list();

        return new ContractStructureResponse(
                id,
                versionNumber,
                version.parserVersion(),
                version.parseStatus(),
                nodes.size(),
                nodes);
    }

    @GetMapping("/{id}/versions/{versionNumber}/original")
    public ResponseEntity<InputStreamResource> downloadOriginal(
            @PathVariable UUID id, @PathVariable int versionNumber) throws IOException {
        var storageKey = jdbcClient.sql("""
                select sa.storage_key
                from contract_version cv
                join source_artifact sa on sa.id = cv.source_artifact_id
                where cv.contract_id = :id and cv.version_number = :versionNumber
                """)
                .param("id", id)
                .param("versionNumber", versionNumber)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException(
                        "Contract version not found: " + id + "/" + versionNumber));

        var stream = documentStorage.open(new StorageKey(storageKey));
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .body(new InputStreamResource(stream));
    }

    private static Integer getNullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }

    private record VersionMeta(UUID id, String parserVersion, String parseStatus) {
    }
}
