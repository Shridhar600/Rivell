package com.rivell.workbench.redline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Deterministic plan resolution: every approved change is resolved against the frozen
 * M1 structure (canonical heading ids and source spans) before any document mutation.
 * No fuzzy matching, no re-resolution after mutation, no model input.
 */
public final class RedlinePlanResolver {

    public static final String RENDERER_VERSION = "rivell-redline-renderer-1";

    private final InsertPlacementStrategy placementStrategy = new InsertPlacementStrategy();

    public DocumentExecutionPlan resolve(
            ApprovedChangeSet set,
            ContractStructure structure,
            Map<UUID, ConceptKey> effectivePrimaryConcepts,
            String author,
            Instant executedAt,
            String sourceSha256) {
        if (set.changeSetVersion() != 1) {
            throw new RedlinePlanException(RedlineFailureCode.UNSUPPORTED_CHANGESET_VERSION, null,
                    "Change set version " + set.changeSetVersion()
                            + " is not supported by this renderer.");
        }
        if (!structure.contractId().equals(set.contractId())
                || structure.versionNumber() != set.contractVersionNumber()) {
            throw new RedlinePlanException(RedlineFailureCode.SOURCE_VERSION_MISMATCH, null,
                    "The frozen structure does not match the approved change set source version.");
        }

        var nodes = structure.nodes();
        var byId = new HashMap<UUID, DocumentNode>();
        for (var node : nodes) {
            byId.put(node.id(), node);
        }
        var regionByHeading = new HashMap<UUID, ContractRegionSegmenter.Region>();
        for (var region : new ContractRegionSegmenter().segment(nodes)) {
            if (!region.isPreamble()) {
                regionByHeading.putIfAbsent(region.headingNodeId(), region);
            }
        }

        var ordered = new ArrayList<>(set.changes());
        ordered.sort(Comparator.comparingInt(ApprovedChange::ordinal));

        var operations = new ArrayList<PlannedDocumentOperation>();
        for (var change : ordered) {
            operations.add(switch (change.actionType()) {
                case REPLACE -> replaceOperation(change, byId, regionByHeading, nodes);
                case DELETE -> deleteOperation(change, byId, regionByHeading, nodes);
                case INSERT -> insertOperation(change, structure, effectivePrimaryConcepts,
                        nodes);
            });
        }

        validateNoOverlap(operations);
        validateFinalParagraphUntouched(operations, nodes);
        return new DocumentExecutionPlan(set.id(), set.changeSetVersion(),
                RENDERER_VERSION, InsertPlacementStrategy.VERSION, author, executedAt,
                sourceSha256, operations);
    }

    private PlannedDocumentOperation replaceOperation(
            ApprovedChange change,
            Map<UUID, DocumentNode> byId,
            Map<UUID, ContractRegionSegmenter.Region> regionByHeading,
            List<DocumentNode> nodes) {
        var region = clauseRegion(change, byId, regionByHeading);
        var deletionTargets = bodyTargets(change, region, byId);
        var paragraphs = splitParagraphs(change.approvedLanguage(), change);
        var heading = heading(change, region, byId);
        var anchorIndex = heading.sourceSpan().paragraphIndex();
        var anchorText = heading.text() == null ? "" : heading.text();
        PlannedInsertion insertion;
        if (deletionTargets.isEmpty()) {
            insertion = new PlannedInsertion(heading.sourceSpan().bodyIndex(), anchorIndex,
                    anchorText, null, null, paragraphs);
        } else {
            var template = deletionTargets.get(0);
            insertion = new PlannedInsertion(heading.sourceSpan().bodyIndex(), anchorIndex,
                    anchorText, template.bodyIndex(), template.paragraphIndex(), paragraphs);
        }
        return new PlannedDocumentOperation(change.id(), change.ordinal(),
                ChangeActionType.REPLACE, deletionTargets, insertion, null,
                label(anchorText));
    }

    private PlannedDocumentOperation deleteOperation(
            ApprovedChange change,
            Map<UUID, DocumentNode> byId,
            Map<UUID, ContractRegionSegmenter.Region> regionByHeading,
            List<DocumentNode> nodes) {
        var region = clauseRegion(change, byId, regionByHeading);
        var targets = new ArrayList<PlannedParagraph>();
        targets.add(paragraph(change, heading(change, region, byId)));
        targets.addAll(bodyTargets(change, region, byId));
        return new PlannedDocumentOperation(change.id(), change.ordinal(),
                ChangeActionType.DELETE, targets, null, null,
                label(heading(change, region, byId).text()));
    }

    private PlannedDocumentOperation insertOperation(
            ApprovedChange change,
            ContractStructure structure,
            Map<UUID, ConceptKey> effectivePrimaryConcepts,
            List<DocumentNode> nodes) {
        var placement = placementStrategy.resolve(change.absenceConcept(), structure,
                effectivePrimaryConcepts);
        var paragraphs = splitParagraphs(change.approvedLanguage(), change);
        var anchor = anchorIdentity(placement.afterBodyIndex(), nodes);
        var insertion = new PlannedInsertion(placement.afterBodyIndex(),
                anchor == null ? null : anchor.sourceSpan().paragraphIndex(),
                anchor == null ? null : (anchor.text() == null ? "" : anchor.text()),
                placement.styleTemplateBodyIndex(), placement.styleTemplateParagraphIndex(),
                paragraphs);
        return new PlannedDocumentOperation(change.id(), change.ordinal(),
                ChangeActionType.INSERT, List.of(), insertion, placement.label(),
                change.absenceConcept().value());
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) {
            return "(unnamed target)";
        }
        var single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 120 ? single.substring(0, 120) : single;
    }

    private DocumentNode anchorIdentity(int afterBodyIndex, List<DocumentNode> nodes) {
        if (afterBodyIndex < 0) {
            return null;
        }
        return nodes.stream()
                .filter(node -> node.sourceSpan().bodyIndex() == afterBodyIndex)
                .filter(node -> node.nodeType() == DocumentNodeType.PARAGRAPH
                        || node.nodeType() == DocumentNodeType.HEADING
                        || node.nodeType() == DocumentNodeType.TITLE)
                .findFirst()
                .orElse(null);
    }

    private ContractRegionSegmenter.Region clauseRegion(
            ApprovedChange change,
            Map<UUID, DocumentNode> byId,
            Map<UUID, ContractRegionSegmenter.Region> regionByHeading) {
        var headingNode = byId.get(change.targetHeadingNodeId());
        if (headingNode == null || headingNode.nodeType() != DocumentNodeType.HEADING) {
            throw new RedlinePlanException(RedlineFailureCode.TARGET_NOT_FOUND, change.id(),
                    "Approved change " + change.ordinal() + " (" + change.actionType()
                            + ") targets a clause that is not present in the frozen structure.");
        }
        var region = regionByHeading.get(headingNode.id());
        if (region == null) {
            throw new RedlinePlanException(RedlineFailureCode.TARGET_NOT_FOUND, change.id(),
                    "Approved change " + change.ordinal() + " (" + change.actionType()
                            + ") targets a clause that is not present in the frozen structure.");
        }
        if (region.kind() != ContractRegionSegmenter.SemanticRegionKind.CLAUSE) {
            throw new RedlinePlanException(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                    change.id(),
                    "Approved change " + change.ordinal()
                            + " targets a region that is not a supported clause.");
        }
        return region;
    }

    private List<PlannedParagraph> bodyTargets(
            ApprovedChange change,
            ContractRegionSegmenter.Region region,
            Map<UUID, DocumentNode> byId) {
        var targets = new ArrayList<PlannedParagraph>();
        for (var textNodeId : region.textNodeIds()) {
            var node = byId.get(textNodeId);
            if (node == null) {
                throw new RedlinePlanException(RedlineFailureCode.TARGET_NOT_FOUND, change.id(),
                        "Approved change " + change.ordinal()
                                + " targets a clause whose content is not present in the "
                                + "frozen structure.");
            }
            if (node.nodeType() != DocumentNodeType.PARAGRAPH) {
                throw new RedlinePlanException(
                        RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE, change.id(),
                        "Approved change " + change.ordinal()
                                + " targets a clause that contains unsupported content.");
            }
            targets.add(paragraph(change, node));
        }
        return targets;
    }

    private PlannedParagraph paragraph(ApprovedChange change, DocumentNode node) {
        return new PlannedParagraph(node.sourceSpan().bodyIndex(),
                node.sourceSpan().paragraphIndex() == null ? -1
                        : node.sourceSpan().paragraphIndex(),
                node.text() == null ? "" : node.text());
    }

    private DocumentNode heading(
            ApprovedChange change,
            ContractRegionSegmenter.Region region,
            Map<UUID, DocumentNode> byId) {
        var node = byId.get(region.headingNodeId());
        if (node == null) {
            throw new RedlinePlanException(RedlineFailureCode.TARGET_NOT_FOUND, change.id(),
                    "Approved change " + change.ordinal()
                            + " targets a clause whose heading is not present in the frozen "
                            + "structure.");
        }
        return node;
    }

    /**
     * Defense in depth beyond M8 target-level conflicts: no paragraph may be deleted by
     * two changes. An insertion anchored after a paragraph deleted by another change is
     * allowed — its position stays deterministic (after that paragraph's slot).
     */
    private void validateNoOverlap(List<PlannedDocumentOperation> operations) {
        var deleted = new HashMap<String, UUID>();
        for (var operation : operations) {
            for (var target : operation.deletionTargets()) {
                var key = target.bodyIndex() + ":" + target.paragraphIndex();
                var previous = deleted.put(key, operation.approvedChangeId());
                if (previous != null) {
                    throw new RedlinePlanException(RedlineFailureCode.CHANGE_OVERLAP,
                            operation.approvedChangeId(),
                            "Approved changes target overlapping document content.");
                }
            }
        }
    }

    /**
     * The body's final paragraph mark is never deleted: Word cannot represent deleting
     * the terminal mark (and the paragraph may carry the section properties). A target
     * containing it fails closed before any rendering.
     */
    private void validateFinalParagraphUntouched(
            List<PlannedDocumentOperation> operations, List<DocumentNode> nodes) {
        var lastParagraphOrdinal = nodes.stream()
                .filter(node -> node.sourceSpan().paragraphIndex() != null)
                .mapToInt(node -> node.sourceSpan().paragraphIndex())
                .max()
                .orElse(-1);
        if (lastParagraphOrdinal < 0) {
            return;
        }
        for (var operation : operations) {
            for (var target : operation.deletionTargets()) {
                if (target.paragraphIndex() == lastParagraphOrdinal) {
                    throw new RedlinePlanException(
                            RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                            operation.approvedChangeId(),
                            "Approved change " + operation.ordinal()
                                    + " would delete the document's final paragraph, "
                                    + "which cannot be represented as a tracked change.");
                }
            }
        }
    }

    /**
     * Infrastructure-only normalization: {@code \r\n}/{@code \r} become {@code \n}, one
     * trailing empty segment is dropped, interior empty lines become empty paragraphs.
     * No other change to the approved text.
     */
    public static List<String> splitParagraphs(String language) {
        return splitParagraphs(language, null);
    }

    private static List<String> splitParagraphs(String language, ApprovedChange change) {
        if (language == null || language.isBlank()) {
            throw new RedlinePlanException(RedlineFailureCode.MISSING_APPROVED_LANGUAGE,
                    change == null ? null : change.id(),
                    "An approved change carries no approved language.");
        }
        var normalized = language.replace("\r\n", "\n").replace('\r', '\n');
        var parts = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (parts.size() > 1 && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        if (parts.stream().allMatch(String::isEmpty)) {
            throw new RedlinePlanException(RedlineFailureCode.MISSING_APPROVED_LANGUAGE,
                    change == null ? null : change.id(),
                    "An approved change carries no approved language.");
        }
        return List.copyOf(parts);
    }
}
