package com.rivell.workbench.redline;

import java.util.List;
import java.util.Objects;

/**
 * A tracked insertion of exact approved paragraphs. {@code afterBodyIndex} is the body
 * content index to insert after; -1 inserts before the first body element.
 * {@code styleTemplate} (nullable) names the paragraph whose formatting is inherited.
 */
public record PlannedInsertion(
        int afterBodyIndex,
        Integer styleTemplateBodyIndex,
        Integer styleTemplateParagraphIndex,
        List<String> paragraphs) {

    public PlannedInsertion {
        if (afterBodyIndex < -1) {
            throw new IllegalArgumentException("insertion index must be >= -1");
        }
        if ((styleTemplateBodyIndex == null) != (styleTemplateParagraphIndex == null)) {
            throw new IllegalArgumentException("style template needs both indices");
        }
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        if (paragraphs.isEmpty()) {
            throw new IllegalArgumentException("an insertion carries at least one paragraph");
        }
        Objects.requireNonNull(paragraphs, "paragraphs");
    }
}
