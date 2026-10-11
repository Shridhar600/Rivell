package com.rivell.workbench.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * One node of a deterministic structural parse. {@code ownerAggregateId} identifies the
 * domain aggregate the tree belongs to (a contract version, a knowledge artifact, ...).
 *
 * <p>{@code runInHeading} is set only on a heading whose paragraph also carries clause text
 * ("5. Term. This Agreement…"): it is the leading part of {@code text} that is the heading
 * (label and title, with its closing punctuation). The rest of the paragraph is clause text, so
 * a change to the clause must also change that part of the heading paragraph.
 */
public record DocumentNode(
        UUID id,
        UUID ownerAggregateId,
        UUID parentId,
        DocumentNodeType nodeType,
        int ordinal,
        String text,
        Integer headingLevel,
        String numberingLabel,
        SourceSpan sourceSpan,
        String rawStyleId,
        String runInHeading) {

    public DocumentNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAggregateId, "ownerAggregateId");
        Objects.requireNonNull(nodeType, "nodeType");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must not be negative");
        }
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        if (headingLevel != null && headingLevel < 1) {
            throw new IllegalArgumentException("Heading level must be positive");
        }
        if (runInHeading != null && (nodeType != DocumentNodeType.HEADING || text == null
                || !text.startsWith(runInHeading) || text.strip().length() <= runInHeading.length())) {
            throw new IllegalArgumentException(
                    "A run-in heading must be a heading whose text starts with it and continues");
        }
    }

    public DocumentNode(
            UUID id, UUID ownerAggregateId, UUID parentId, DocumentNodeType nodeType, int ordinal,
            String text, Integer headingLevel, String numberingLabel, SourceSpan sourceSpan,
            String rawStyleId) {
        this(id, ownerAggregateId, parentId, nodeType, ordinal, text, headingLevel, numberingLabel,
                sourceSpan, rawStyleId, null);
    }
}
