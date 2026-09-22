package com.rivell.workbench.contracts;

import java.util.Objects;

/**
 * Location of a node in the source document. {@code bodyIndex} is the true index of the element in
 * the main document body's content list; skipped elements cannot shift it.
 * {@code paragraphIndex} / {@code tableIndex} are document-order counts of their kind.
 */
public record SourceSpan(
        String partPath,
        int bodyIndex,
        Integer paragraphIndex,
        Integer tableIndex,
        Integer rowIndex,
        Integer cellIndex,
        Integer runStart,
        Integer runEnd) {

    public SourceSpan {
        if (partPath == null || partPath.isBlank()) {
            throw new IllegalArgumentException("Part path must not be blank");
        }
        if (bodyIndex < 0) {
            throw new IllegalArgumentException("Body index must not be negative");
        }
    }

    public static SourceSpan ofBody(String partPath) {
        return new SourceSpan(partPath, 0, null, null, null, null, null, null);
    }

    public static SourceSpan ofParagraph(String partPath, int bodyIndex, int paragraphIndex) {
        return new SourceSpan(partPath, bodyIndex, paragraphIndex, null, null, null, null, null);
    }

    public static SourceSpan ofTable(String partPath, int bodyIndex, int tableIndex) {
        return new SourceSpan(partPath, bodyIndex, null, tableIndex, null, null, null, null);
    }

    public static SourceSpan ofRow(String partPath, int bodyIndex, int tableIndex, int rowIndex) {
        return new SourceSpan(partPath, bodyIndex, null, tableIndex, rowIndex, null, null, null);
    }

    public static SourceSpan ofCell(
            String partPath, int bodyIndex, int tableIndex, int rowIndex, int cellIndex) {
        return new SourceSpan(partPath, bodyIndex, null, tableIndex, rowIndex, cellIndex, null, null);
    }

    public static SourceSpan ofRuns(
            String partPath, int bodyIndex, int paragraphIndex, int runStart, int runEnd) {
        return new SourceSpan(partPath, bodyIndex, paragraphIndex, null, null, null, runStart, runEnd);
    }

    public boolean hasNonNegativeIndices() {
        return bodyIndex >= 0
                && (paragraphIndex == null || paragraphIndex >= 0)
                && (tableIndex == null || tableIndex >= 0)
                && (rowIndex == null || rowIndex >= 0)
                && (cellIndex == null || cellIndex >= 0)
                && (runStart == null || runStart >= 0)
                && (runEnd == null || runEnd >= 0);
    }
}
