package com.rivell.workbench.redline;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

/**
 * Deterministic DW-030 INSERT placement (document position only, never legal substance):
 * code-owned ordered anchor concepts, then a bounded structural fallback. Versioned by
 * {@link #VERSION}; never uses a model, knowledge, or fuzzy text matching.
 */
public final class InsertPlacementStrategy {

    public static final String VERSION = "rivell-insert-placement-1";

    private static final Map<ConceptKey, List<ConceptKey>> ANCHOR_CONCEPTS = Map.of(
            CoreConcepts.LIMITATION_OF_LIABILITY,
            List.of(CoreConcepts.INDEMNIFICATION, CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                    CoreConcepts.TERM),
            CoreConcepts.INDEMNIFICATION,
            List.of(CoreConcepts.LIMITATION_OF_LIABILITY, CoreConcepts.TERM),
            CoreConcepts.TERM,
            List.of(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS, CoreConcepts.LIMITATION_OF_LIABILITY),
            CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
            List.of(CoreConcepts.TERM, CoreConcepts.LIMITATION_OF_LIABILITY),
            CoreConcepts.GOVERNING_LAW,
            List.of(CoreConcepts.TERM, CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                    CoreConcepts.INDEMNIFICATION));

    /** Resolved insertion position; {@code afterBodyIndex = -1} inserts at the start. */
    public record Placement(
            String label,
            int afterBodyIndex,
            Integer styleTemplateBodyIndex,
            Integer styleTemplateParagraphIndex) {
    }

    public Placement resolve(
            ConceptKey concept,
            ContractStructure structure,
            Map<UUID, ConceptKey> effectivePrimaryConcepts) {
        var nodes = structure.nodes();
        var byId = new java.util.HashMap<UUID, DocumentNode>();
        for (var node : nodes) {
            byId.put(node.id(), node);
        }
        var regions = new ContractRegionSegmenter().segment(nodes);

        for (var anchorConcept : ANCHOR_CONCEPTS.getOrDefault(concept, List.of())) {
            ContractRegionSegmenter.Region match = null;
            for (var region : regions) {
                if (region.kind() == ContractRegionSegmenter.SemanticRegionKind.CLAUSE
                        && !region.isPreamble()
                        && anchorConcept.equals(
                                effectivePrimaryConcepts.get(region.headingNodeId()))) {
                    match = region;
                }
            }
            if (match != null) {
                var lastIndex = lastElementBodyIndex(match, byId);
                var template = lastBodyParagraph(match, byId);
                return new Placement("AFTER_CLAUSE[" + anchorConcept + "]",
                        lastIndex,
                        template == null ? null : template.sourceSpan().bodyIndex(),
                        template == null ? null : template.sourceSpan().paragraphIndex());
            }
        }

        for (var region : regions) {
            if (region.kind() == ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK
                    || region.kind() == ContractRegionSegmenter.SemanticRegionKind.SCHEDULE) {
                return new Placement("BEFORE_SIGNATURE_BLOCK",
                        firstElementBodyIndex(region, byId) - 1, null, null);
            }
        }

        var bodyId = bodyIdOf(nodes);
        var lastTopLevel = -1;
        DocumentNode lastParagraph = null;
        for (var node : nodes) {
            if (node.parentId() != null && node.parentId().equals(bodyId)
                    && node.nodeType() != DocumentNodeType.BODY) {
                if (node.sourceSpan().bodyIndex() > lastTopLevel) {
                    lastTopLevel = node.sourceSpan().bodyIndex();
                }
                if (node.nodeType() == DocumentNodeType.PARAGRAPH
                        && (lastParagraph == null || node.sourceSpan().bodyIndex()
                                > lastParagraph.sourceSpan().bodyIndex())) {
                    lastParagraph = node;
                }
            }
        }
        if (lastTopLevel < 0) {
            throw new RedlinePlanException(RedlineFailureCode.INSERT_PLACEMENT_UNRESOLVED, null,
                    "Rivell cannot tell where to insert the new clause in this contract.");
        }
        return new Placement("END_OF_BODY", lastTopLevel,
                lastParagraph == null ? null : lastParagraph.sourceSpan().bodyIndex(),
                lastParagraph == null ? null : lastParagraph.sourceSpan().paragraphIndex());
    }

    private static int lastElementBodyIndex(
            ContractRegionSegmenter.Region region, Map<UUID, DocumentNode> byId) {
        var last = heading(region, byId).sourceSpan().bodyIndex();
        for (var textNodeId : region.textNodeIds()) {
            var node = byId.get(textNodeId);
            if (node != null) {
                last = Math.max(last, node.sourceSpan().bodyIndex());
            }
        }
        return last;
    }

    private static int firstElementBodyIndex(
            ContractRegionSegmenter.Region region, Map<UUID, DocumentNode> byId) {
        var first = heading(region, byId).sourceSpan().bodyIndex();
        for (var textNodeId : region.textNodeIds()) {
            var node = byId.get(textNodeId);
            if (node != null) {
                first = Math.min(first, node.sourceSpan().bodyIndex());
            }
        }
        return first;
    }

    private static DocumentNode lastBodyParagraph(
            ContractRegionSegmenter.Region region, Map<UUID, DocumentNode> byId) {
        DocumentNode last = null;
        for (var textNodeId : region.textNodeIds()) {
            var node = byId.get(textNodeId);
            if (node != null && node.nodeType() == DocumentNodeType.PARAGRAPH
                    && (last == null || node.sourceSpan().bodyIndex()
                            > last.sourceSpan().bodyIndex())) {
                last = node;
            }
        }
        return last;
    }

    private static DocumentNode heading(
            ContractRegionSegmenter.Region region, Map<UUID, DocumentNode> byId) {
        var node = byId.get(region.headingNodeId());
        if (node == null) {
            throw new RedlinePlanException(RedlineFailureCode.TARGET_NOT_FOUND, null,
                    "Rivell cannot find the heading of a clause in this contract.");
        }
        return node;
    }

    private static UUID bodyIdOf(List<DocumentNode> nodes) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.BODY)
                .map(DocumentNode::id)
                .findFirst()
                .orElseThrow(() -> new RedlinePlanException(
                        RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE, null,
                        "Rivell cannot find the body of this contract."));
    }
}
