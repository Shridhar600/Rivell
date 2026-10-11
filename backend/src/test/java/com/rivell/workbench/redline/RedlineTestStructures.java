package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.ConceptKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.contracts.SourceSpan;

/** Synthetic M1 structures and frozen artifacts for pure redline plan tests. */
public final class RedlineTestStructures {

    private RedlineTestStructures() {
    }

    public record BodyElement(String text) {
    }

    public record Table(String... cellTexts) {
    }

    public static ContractStructure structure(UUID contractId, UUID versionId,
            List<Object> clauses) {
        var nodes = new ArrayList<DocumentNode>();
        var bodyId = UUID.randomUUID();
        nodes.add(new DocumentNode(bodyId, versionId, null, DocumentNodeType.BODY, 0,
                null, null, null, SourceSpan.ofBody("word/document.xml"), null));
        var bodyIndex = 0;
        var paragraphIndex = 0;
        var tableIndex = 0;
        var ordinal = 1;
        for (var clause : clauses) {
            if (!(clause instanceof Clause specification)) {
                throw new IllegalArgumentException("expected a clause");
            }
            nodes.add(new DocumentNode(UUID.randomUUID(), versionId, bodyId,
                    DocumentNodeType.HEADING, ordinal++, specification.heading(), 1, null,
                    SourceSpan.ofParagraph("word/document.xml", bodyIndex, paragraphIndex),
                    "Heading1"));
            bodyIndex++;
            paragraphIndex++;
            for (var element : specification.body()) {
                if (element instanceof Table table) {
                    var tableId = UUID.randomUUID();
                    var tableSpan = SourceSpan.ofTable("word/document.xml", bodyIndex, tableIndex);
                    nodes.add(new DocumentNode(tableId, versionId, bodyId,
                            DocumentNodeType.TABLE, ordinal++, null, null, null, tableSpan, null));
                    var rowIndex = 0;
                    for (var cellText : table.cellTexts()) {
                        var rowId = UUID.randomUUID();
                        nodes.add(new DocumentNode(rowId, versionId, tableId,
                                DocumentNodeType.TABLE_ROW, rowIndex, null, null, null,
                                SourceSpan.ofRow("word/document.xml", bodyIndex, tableIndex,
                                        rowIndex), null));
                        nodes.add(new DocumentNode(UUID.randomUUID(), versionId, rowId,
                                DocumentNodeType.TABLE_CELL, 0, cellText, null, null,
                                SourceSpan.ofCell("word/document.xml", bodyIndex, tableIndex,
                                        rowIndex, 0), null));
                        rowIndex++;
                    }
                    tableIndex++;
                    bodyIndex++;
                } else if (element instanceof BodyElement paragraph) {
                    nodes.add(new DocumentNode(UUID.randomUUID(), versionId, bodyId,
                            DocumentNodeType.PARAGRAPH, ordinal++, paragraph.text(), null, null,
                            SourceSpan.ofParagraph("word/document.xml", bodyIndex, paragraphIndex),
                            null));
                    bodyIndex++;
                    paragraphIndex++;
                } else {
                    throw new IllegalArgumentException("unsupported test element");
                }
            }
        }
        return new ContractStructure(contractId, 1, "rivell-docx-parser-2",
                ParseStatus.OK, List.of(), nodes);
    }

    public record Clause(String heading, List<Object> body) {
    }

    public static Clause clause(String heading, Object... body) {
        return new Clause(heading, List.of(body));
    }

    public static BodyElement text(String text) {
        return new BodyElement(text);
    }

    public static Map<UUID, ConceptKey> concepts(
            ContractStructure structure, Map<String, ConceptKey> byHeadingText) {
        var byId = new LinkedHashMap<UUID, ConceptKey>();
        for (var node : structure.nodes()) {
            if (node.nodeType() == DocumentNodeType.HEADING
                    && node.text() != null && byHeadingText.containsKey(node.text())) {
                byId.put(node.id(), byHeadingText.get(node.text()));
            }
        }
        return byId;
    }

    public static UUID headingId(ContractStructure structure, String headingText) {
        return structure.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .filter(node -> headingText.equals(node.text()))
                .map(DocumentNode::id)
                .findFirst()
                .orElseThrow();
    }

    public static ApprovedChange replace(UUID changeSetId, int ordinal, UUID headingNodeId,
            String language) {
        return new ApprovedChange(UUID.randomUUID(), UUID.randomUUID(), changeSetId,
                UUID.randomUUID(), ordinal, UUID.randomUUID(), UUID.randomUUID(),
                ChangeActionType.REPLACE, ChangeTargetKind.CLAUSE, headingNodeId, null,
                language, ChangeLanguageSource.HUMAN_EDIT, null);
    }

    public static ApprovedChange delete(UUID changeSetId, int ordinal, UUID headingNodeId) {
        return new ApprovedChange(UUID.randomUUID(), UUID.randomUUID(), changeSetId,
                UUID.randomUUID(), ordinal, UUID.randomUUID(), UUID.randomUUID(),
                ChangeActionType.DELETE, ChangeTargetKind.CLAUSE, headingNodeId, null,
                null, ChangeLanguageSource.NONE, null);
    }

    public static ApprovedChange insert(UUID changeSetId, int ordinal, ConceptKey concept,
            String language) {
        return new ApprovedChange(UUID.randomUUID(), UUID.randomUUID(), changeSetId,
                UUID.randomUUID(), ordinal, UUID.randomUUID(), UUID.randomUUID(),
                ChangeActionType.INSERT, ChangeTargetKind.ABSENCE, null, concept,
                language, ChangeLanguageSource.HUMAN_EDIT, null);
    }

    public static ApprovedChangeSet set(UUID organizationId, UUID contractId, UUID versionId,
            int changeSetVersion, List<ApprovedChange> changes) {
        var changeSetId = changes.isEmpty()
                ? UUID.randomUUID() : changes.get(0).changeSetId();
        return new ApprovedChangeSet(changeSetId, organizationId, UUID.randomUUID(),
                UUID.randomUUID(), contractId, versionId, 1, changeSetVersion,
                "c".repeat(64), UUID.randomUUID(),
                java.time.Instant.parse("2026-09-26T00:00:00Z"), changes);
    }
}
