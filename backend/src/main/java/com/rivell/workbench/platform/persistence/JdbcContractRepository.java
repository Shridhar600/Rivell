package com.rivell.workbench.platform.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.Contract;
import com.rivell.workbench.contracts.ContractListEntry;
import com.rivell.workbench.contracts.ContractRepository;
import com.rivell.workbench.contracts.ContractSource;
import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.ContractVersionSummary;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.ParseCoverage;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.contracts.SourceSpan;
import java.time.Instant;

@Repository
class JdbcContractRepository implements ContractRepository {

    /**
     * The latest-version projection, shared by the single-contract read and the list so
     * the two surfaces cannot drift. {@code node_count} is the one correlated subquery,
     * evaluated per row inside a single statement rather than as a round trip per row.
     */
    private static final String VERSION_SUMMARY_COLUMNS = """
            cv.id, cv.contract_id, cv.version_number, cv.parse_status, cv.parse_warnings,
            cv.parser_version, sa.sha256, sa.filename,
            (select count(*) from document_node n where n.contract_version_id = cv.id)
                as node_count
            """;

    private static final String VERSION_SUMMARY_SQL = """
            select %s
            from contract_version cv
            join contract c on c.id = cv.contract_id
            join source_artifact sa on sa.id = cv.source_artifact_id
            """.formatted(VERSION_SUMMARY_COLUMNS);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcContractRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Contract> findContract(UUID contractId, UUID organizationId) {
        return jdbcClient.sql("""
                        select id, organization_id, title, created_at
                        from contract
                        where id = :id and organization_id = :organizationId
                        """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new Contract(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("organization_id")),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<ContractVersionSummary> findLatestVersionSummary(UUID contractId, UUID organizationId) {
        return jdbcClient.sql(VERSION_SUMMARY_SQL + """
                        where c.id = :contractId and c.organization_id = :organizationId
                        order by cv.version_number desc
                        limit 1
                        """)
                .param("contractId", contractId)
                .param("organizationId", organizationId)
                .query(this::mapVersionSummary)
                .optional();
    }

    @Override
    public Optional<ContractVersionSummary> findVersionSummary(
            UUID contractId, int versionNumber, UUID organizationId) {
        return jdbcClient.sql(VERSION_SUMMARY_SQL + """
                        where c.id = :contractId and c.organization_id = :organizationId
                          and cv.version_number = :versionNumber
                        """)
                .param("contractId", contractId)
                .param("organizationId", organizationId)
                .param("versionNumber", versionNumber)
                .query(this::mapVersionSummary)
                .optional();
    }

    @Override
    public java.util.List<UUID> findHeadingNodeIds(UUID versionId) {
        return jdbcClient.sql("""
                select id from document_node
                where contract_version_id = :versionId and node_type = 'HEADING'
                """)
                .param("versionId", versionId)
                .query(UUID.class)
                .list();
    }

    @Override
    public ContractStructure loadStructure(UUID versionId) {
        var versionMeta = jdbcClient.sql("""
                        select cv.contract_id, cv.version_number, cv.parser_version, cv.parse_status, cv.parse_warnings
                        from contract_version cv
                        where cv.id = :versionId
                        """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new VersionMeta(
                        UUID.fromString(rs.getString("contract_id")),
                        rs.getInt("version_number"),
                        rs.getString("parser_version"),
                        ParseStatus.valueOf(rs.getString("parse_status")),
                        readWarnings(rs.getString("parse_warnings"))))
                .single();

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
                select id, parent_id, node_type, ordinal, text, heading_level, numbering_label,
                       part_path, body_index, paragraph_index, table_index, row_index, cell_index,
                       run_start, run_end, raw_style_id
                from tree
                order by sort_path
                """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new DocumentNode(
                        UUID.fromString(rs.getString("id")),
                        versionId,
                        rs.getString("parent_id") == null
                                ? null : UUID.fromString(rs.getString("parent_id")),
                        DocumentNodeType.valueOf(rs.getString("node_type")),
                        rs.getInt("ordinal"),
                        rs.getString("text"),
                        nullableInt(rs, "heading_level"),
                        rs.getString("numbering_label"),
                        new SourceSpan(
                                rs.getString("part_path"),
                                rs.getInt("body_index"),
                                nullableInt(rs, "paragraph_index"),
                                nullableInt(rs, "table_index"),
                                nullableInt(rs, "row_index"),
                                nullableInt(rs, "cell_index"),
                                nullableInt(rs, "run_start"),
                                nullableInt(rs, "run_end")),
                        rs.getString("raw_style_id")))
                .list();

        return new ContractStructure(
                versionMeta.contractId(),
                versionMeta.versionNumber(),
                versionMeta.parserVersion(),
                versionMeta.parseStatus(),
                versionMeta.parseWarnings(),
                nodes);
    }

    @Override
    public Optional<ContractSource> findSourceForVersion(UUID versionId, UUID organizationId) {
        return jdbcClient.sql("""
                        select sa.id, sa.organization_id, sa.filename, sa.content_type,
                               sa.size_bytes, sa.sha256, sa.storage_key
                        from source_artifact sa
                        join contract_version cv on cv.source_artifact_id = sa.id
                        where cv.id = :versionId and sa.organization_id = :organizationId
                        """)
                .param("versionId", versionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ContractSource(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("organization_id")),
                        rs.getString("filename"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("storage_key")))
                .optional();
    }

    @Override
    @Transactional
    public void insertSourceArtifact(
            UUID artifactId, UUID organizationId, String filename, String contentType,
            long sizeBytes, String sha256, String storageKey) {
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:artifactId, :organizationId, :filename, :contentType, :sizeBytes, :sha256, :storageKey, :createdAt)
                """)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("filename", filename)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("sha256", sha256)
                .param("storageKey", storageKey)
                .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional
    public void insertContract(
            UUID contractId, UUID organizationId, String title, Instant createdAt) {
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:contractId, :organizationId, :title, :createdAt)
                """)
                .param("contractId", contractId)
                .param("organizationId", organizationId)
                .param("title", title)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional
    public void insertVersion(
            UUID versionId, UUID contractId, UUID sourceArtifactId, int versionNumber,
            com.rivell.workbench.contracts.ContractRole role, String parserVersion,
            ParseStatus parseStatus, List<String> parseWarnings, Instant createdAt) {
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id, role,
                   parser_version, parse_status, parse_warnings, created_at)
                values (:versionId, :contractId, :versionNumber, :sourceArtifactId,
                        (select organization_id from contract where id = :contractId),
                        :role, :parserVersion, :parseStatus, cast(:warnings as jsonb), :createdAt)
                """)
                .param("versionId", versionId)
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .param("sourceArtifactId", sourceArtifactId)
                .param("role", role.name())
                .param("parserVersion", parserVersion)
                .param("parseStatus", parseStatus.name())
                .param("warnings", toJson(parseWarnings))
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional
    public void updateVersionParseOutcome(
            UUID versionId, String parserVersion, ParseStatus status, List<String> warnings,
            ParseCoverage coverage) {
        var updated = jdbcClient.sql("""
                update contract_version
                set parser_version = :parserVersion, parse_status = :parseStatus,
                    parse_warnings = cast(:warnings as jsonb),
                    parse_coverage = cast(:coverage as jsonb)
                where id = :versionId
                """)
                .param("versionId", versionId)
                .param("parserVersion", parserVersion)
                .param("parseStatus", status.name())
                .param("warnings", toJson(warnings))
                .param("coverage", coverage == null ? null : writeJson(coverage))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Version parse outcome not recorded: " + versionId);
        }
    }

    @Override
    @Transactional
    public void updateContractTitle(UUID contractId, String title) {
        jdbcClient.sql("update contract set title = :title where id = :contractId")
                .param("contractId", contractId)
                .param("title", title)
                .update();
    }

    @Override
    @Transactional
    public void insertNodes(UUID contractVersionId, List<DocumentNode> nodes) {
        for (var node : nodes) {
            var span = node.sourceSpan();
            jdbcClient.sql("""
                    insert into document_node
                      (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                       numbering_label, part_path, body_index, paragraph_index, table_index,
                       row_index, cell_index, run_start, run_end, raw_style_id)
                    values (:nodeId, :versionId, :parentId, :nodeType, :ordinal, :text, :headingLevel,
                            :numberingLabel, :partPath, :bodyIndex, :paragraphIndex, :tableIndex,
                            :rowIndex, :cellIndex, :runStart, :runEnd, :rawStyleId)
                    """)
                    .param("nodeId", node.id())
                    .param("versionId", contractVersionId)
                    .param("parentId", node.parentId())
                    .param("nodeType", node.nodeType().name())
                    .param("ordinal", node.ordinal())
                    .param("text", node.text())
                    .param("headingLevel", node.headingLevel())
                    .param("numberingLabel", node.numberingLabel())
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

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ContractListEntry> findContracts(
            UUID organizationId, java.time.Instant afterCreatedAt, UUID afterId, int limit) {
        var cursor = "";
        if (afterCreatedAt != null && afterId != null) {
            cursor = """
                    and (c.created_at, c.id) < (
                          cast(:afterCreatedAt as timestamptz), cast(:afterId as uuid))
                    """;
        }
        var client = jdbcClient.sql("""
                select c.title, c.created_at,
                       %s
                from contract c
                join contract_version cv
                  on cv.contract_id = c.id
                 and cv.version_number = (
                        select max(newer.version_number) from contract_version newer
                        where newer.contract_id = c.id)
                join source_artifact sa
                  on sa.id = cv.source_artifact_id
                 and sa.organization_id = c.organization_id
                where c.organization_id = :organizationId
                """.formatted(VERSION_SUMMARY_COLUMNS) + cursor + """
                order by c.created_at desc, c.id desc
                limit :limit
                """)
                .param("organizationId", organizationId)
                .param("limit", limit + 1);
        if (afterCreatedAt != null && afterId != null) {
            // Bound at full TIMESTAMPTZ precision: a truncated resume position would skip
            // contracts sharing that millisecond.
            client = client
                    .param("afterCreatedAt",
                            OffsetDateTime.ofInstant(afterCreatedAt, ZoneOffset.UTC))
                    .param("afterId", afterId.toString());
        }
        return client
                .query((rs, rowNum) -> new ContractListEntry(
                        // contract_version.contract_id equals contract.id by the join, so
                        // the row's contract id is read from the same label the single-contract
                        // read uses rather than selecting a second column under that name.
                        rs.getObject("contract_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        mapVersionSummary(rs, rowNum)))
                .list();
    }

    private ContractVersionSummary mapVersionSummary(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ContractVersionSummary(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("contract_id")),
                rs.getInt("version_number"),
                ParseStatus.valueOf(rs.getString("parse_status")),
                readWarnings(rs.getString("parse_warnings")),
                rs.getString("parser_version"),
                rs.getString("sha256"),
                rs.getString("filename"),
                rs.getInt("node_count"));
    }

    @Override
    public Optional<ParseCoverage> findParseCoverage(UUID versionId) {
        return jdbcClient.sql("select parse_coverage from contract_version where id = :versionId")
                .param("versionId", versionId)
                .query((rs, rowNum) -> Optional.ofNullable(rs.getString("parse_coverage")))
                .optional()
                .flatMap(json -> json)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ParseCoverage.class);
                    } catch (Exception e) {
                        throw new IllegalStateException("Unreadable parse coverage for a version", e);
                    }
                });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private List<String> readWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable parse warnings for a version", e);
        }
    }

    private String toJson(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings == null ? List.of() : warnings);
        } catch (Exception e) {
            throw new IllegalStateException("Unwritable parse warnings", e);
        }
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }

    private record VersionMeta(
            UUID contractId, int versionNumber, String parserVersion,
            ParseStatus parseStatus, List<String> parseWarnings) {
    }
}
