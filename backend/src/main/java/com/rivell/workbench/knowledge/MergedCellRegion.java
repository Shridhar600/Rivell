package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provenance-preserving merged cell range of a spreadsheet (0-based, inclusive bounds).
 * The top-left cell is the anchor and carries the text; other coordinates are members.
 */
public record MergedCellRegion(
        int firstRow,
        int lastRow,
        int firstColumn,
        int lastColumn) {

    public MergedCellRegion {
        if (firstRow < 0 || lastRow < firstRow) {
            throw new IllegalArgumentException("Invalid merged region rows");
        }
        if (firstColumn < 0 || lastColumn < firstColumn) {
            throw new IllegalArgumentException("Invalid merged region columns");
        }
    }

    public boolean covers(int row, int column) {
        return row >= firstRow && row <= lastRow && column >= firstColumn && column <= lastColumn;
    }

    public boolean isAnchor(int row, int column) {
        return row == firstRow && column == firstColumn;
    }

    public static List<MergedCellRegion> copyOf(List<MergedCellRegion> regions) {
        return regions == null ? List.of() : List.copyOf(regions);
    }
}
