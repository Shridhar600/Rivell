package com.rivell.workbench.platform.parsing;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentStructureParser;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeNodeType;
import com.rivell.workbench.knowledge.KnowledgeSourceNode;
import com.rivell.workbench.knowledge.KnowledgeStructureExtractor;
import com.rivell.workbench.knowledge.StructureExtraction;
import java.util.Locale;

/**
 * Knowledge-side DOCX structural extraction. Delegates to the contract document parser
 * (M1, reused without duplication) and maps the resulting tree into the knowledge
 * module's provenance model. Deterministic; no legal interpretation.
 */
@Component
public class DocxKnowledgeExtractor implements KnowledgeStructureExtractor {

    private final DocumentStructureParser documentStructureParser;

    public DocxKnowledgeExtractor(DocumentStructureParser documentStructureParser) {
        this.documentStructureParser = documentStructureParser;
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public StructureExtraction extractStructure(UUID artifactId, InputStream source) {
        var result = documentStructureParser.parse(source, artifactId);
        var nodes = result.nodes().stream()
                .map(node -> toKnowledgeNode(artifactId, node))
                .toList();
        var status = result.status() == ParseStatus.FAILED
                ? IngestStatus.FAILED
                : IngestStatus.EXTRACTED;
        return new StructureExtraction(result.parserVersion(), status, result.warnings(), nodes);
    }

    private static KnowledgeSourceNode toKnowledgeNode(UUID artifactId, DocumentNode node) {
        return new KnowledgeSourceNode(
                node.id(),
                artifactId,
                node.parentId(),
                mapType(node.nodeType()),
                node.ordinal(),
                node.text(),
                node.headingLevel(),
                node.numberingLabel(),
                node.sourceSpan().partPath(),
                node.sourceSpan().bodyIndex(),
                node.sourceSpan().paragraphIndex(),
                node.sourceSpan().tableIndex(),
                node.sourceSpan().rowIndex(),
                node.sourceSpan().cellIndex(),
                node.sourceSpan().runStart(),
                node.sourceSpan().runEnd(),
                null,
                null,
                null,
                node.rawStyleId());
    }

    private static KnowledgeNodeType mapType(com.rivell.workbench.contracts.DocumentNodeType nodeType) {
        return KnowledgeNodeType.valueOf(nodeType.name());
    }
}
