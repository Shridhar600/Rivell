package com.redstar.workbench.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.foundation.StorageKey;
import com.redstar.workbench.organization.IdentityProvider;

@Service
public class ContractIngestionService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentStorage documentStorage;
    private final DocumentStructureParser documentStructureParser;
    private final IdentityProvider identityProvider;
    private final JdbcClient jdbcClient;

    public ContractIngestionService(
            DocumentStorage documentStorage,
            DocumentStructureParser documentStructureParser,
            IdentityProvider identityProvider,
            JdbcClient jdbcClient) {
        this.documentStorage = documentStorage;
        this.documentStructureParser = documentStructureParser;
        this.identityProvider = identityProvider;
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public ContractIngestionResult ingest(String filename, String contentType, InputStream source)
            throws IOException {
        var normalizedFilename = requireDocxFilename(filename);
        var bytes = source.readAllBytes();
        var sha256 = sha256Hex(bytes);
        var organizationId = identityProvider.currentIdentity().organizationId();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        var artifactId = UUID.randomUUID();
        var storageKey = new StorageKey(
                organizationId + "/contracts/" + artifactId + ".docx");
        documentStorage.store(storageKey, new java.io.ByteArrayInputStream(bytes));

        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:id, :orgId, :filename, :contentType, :sizeBytes, :sha256, :storageKey, :createdAt)
                """)
                .param("id", artifactId)
                .param("orgId", organizationId)
                .param("filename", normalizedFilename)
                .param("contentType", contentType == null || contentType.isBlank()
                        ? DOCX_CONTENT_TYPE
                        : contentType)
                .param("sizeBytes", (long) bytes.length)
                .param("sha256", sha256)
                .param("storageKey", storageKey.value())
                .param("createdAt", now)
                .update();

        var contractId = UUID.randomUUID();
        var contractVersionId = UUID.randomUUID();

        var parseResult = documentStructureParser.parse(
                new java.io.ByteArrayInputStream(bytes), contractVersionId);

        var title = parseResult.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TITLE)
                .map(DocumentNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);

        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, :title, :createdAt)
                """)
                .param("id", contractId)
                .param("orgId", organizationId)
                .param("title", title)
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, 'ORIGINAL', :parserVersion, :parseStatus,
                        cast(:warnings as jsonb), :createdAt)
                """)
                .param("id", contractVersionId)
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("parserVersion", parseResult.parserVersion())
                .param("parseStatus", parseResult.status().name())
                .param("warnings", toJsonArray(parseResult.warnings()))
                .param("createdAt", now)
                .update();

        insertNodes(contractVersionId, parseResult.nodes());

        return new ContractIngestionResult(
                contractId,
                contractVersionId,
                artifactId,
                1,
                parseResult.status(),
                sha256,
                storageKey.value(),
                parseResult.nodes().size());
    }

    private void insertNodes(UUID contractVersionId, java.util.List<DocumentNode> nodes) {
        for (var node : nodes) {
            var span = node.sourceSpan();
            jdbcClient.sql("""
                    insert into document_node
                      (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                       part_path, body_index, paragraph_index, table_index, row_index, cell_index,
                       run_start, run_end, raw_style_id)
                    values (:id, :versionId, :parentId, :nodeType, :ordinal, :text, :headingLevel,
                            :partPath, :bodyIndex, :paragraphIndex, :tableIndex, :rowIndex, :cellIndex,
                            :runStart, :runEnd, :rawStyleId)
                    """)
                    .param("id", node.id())
                    .param("versionId", contractVersionId)
                    .param("parentId", node.parentId())
                    .param("nodeType", node.nodeType().name())
                    .param("ordinal", node.ordinal())
                    .param("text", node.text())
                    .param("headingLevel", node.headingLevel())
                    .param("partPath", span.partPath())
                    .param("bodyIndex", span.bodyIndex())
                    .param("paragraphIndex", span.paragraphIndex())
                    .param("tableIndex", span.tableIndex())
                    .param("rowIndex", span.rowIndex())
                    .param("cellIndex", span.cellIndex())
                    .param("runStart", span.runStart())
                    .param("runEnd", span.runEnd())
                    .param("rawStyleId", node.rawStyleId())
                    .update();
        }
    }

    private String requireDocxFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        var normalized = filename.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported");
        }
        return normalized;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String toJsonArray(java.util.List<String> values) {
        var escaped = values.stream()
                .map(value -> "\"" + value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t") + "\"")
                .toList();
        return "[" + String.join(",", escaped) + "]";
    }
}
