package com.rivell.workbench.platform.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeNodeType;
import com.rivell.workbench.knowledge.KnowledgeSourceNode;
import com.rivell.workbench.knowledge.KnowledgeStructureExtractor;
import com.rivell.workbench.knowledge.MergedCellRegion;
import com.rivell.workbench.knowledge.StructureExtraction;
import java.util.Locale;

/**
 * Deterministic XLSX structural extraction via Apache POI (Architecture Decision #7.5).
 * Preserves workbook/sheet/row/cell structure, sheet names, 0-based row/column provenance,
 * and merged regions (text anchors at the top-left cell of a region; member cells carry
 * their region). Cell values render through {@link DataFormatter}; formulas are never
 * evaluated (cached result only, no code-execution surface). The package zip is bounded
 * before POI loads it.
 */
@Component
public class XlsxKnowledgeExtractor implements KnowledgeStructureExtractor {

    public static final String PARSER_VERSION = "rivell-xlsx-parser-1";

    private static final String WORKBOOK_PART_PATH = "xl/workbook.xml";

    private final DocxParserProperties properties;
    private final DataFormatter formatter = new DataFormatter();

    public XlsxKnowledgeExtractor(DocxParserProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    @Override
    public StructureExtraction extractStructure(UUID artifactId, InputStream source) {
        try {
            var bytes = source.readAllBytes();
            boundedScan(bytes);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var nodes = new ArrayList<KnowledgeSourceNode>();
                var workbookId = UUID.randomUUID();
                nodes.add(new KnowledgeSourceNode(
                        workbookId, artifactId, null, KnowledgeNodeType.WORKBOOK, 0,
                        null, null, null, WORKBOOK_PART_PATH, 0,
                        null, null, null, null, null, null,
                        null, null, null, null));

                var stringCells = new ArrayList<String>();
                for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                    addSheet(artifactId, workbookId, sheetIndex, workbook.getSheetAt(sheetIndex), nodes,
                            stringCells);
                }
                var warnings = new ArrayList<String>();
                var coverage = coverage(bytes, stringCells, warnings);
                return new StructureExtraction(PARSER_VERSION, IngestStatus.EXTRACTED, warnings, nodes,
                        coverage);
            }
        } catch (Exception e) {
            return StructureExtraction.failed(
                    PARSER_VERSION,
                    List.of("The workbook could not be parsed (" + e.getClass().getSimpleName() + ")"));
        }
    }

    /** The coverage check never fails an extraction: a check that cannot run is a warning. */
    private static com.rivell.workbench.contracts.ParseCoverage coverage(
            byte[] bytes, List<String> stringCells, List<String> warnings) {
        try {
            return TextCoverage.measureTexts(XlsxTextInventory.read(bytes), stringCells);
        } catch (Exception e) {
            warnings.add("COVERAGE_CHECK_FAILED");
            return null;
        }
    }

    private void addSheet(
            UUID artifactId, UUID workbookId, int sheetIndex, XSSFSheet poiSheet,
            List<KnowledgeSourceNode> nodes, List<String> stringCells) {
        var sheetId = UUID.randomUUID();
        var sheetName = poiSheet.getSheetName();
        var partPath = partPath(poiSheet);
        nodes.add(new KnowledgeSourceNode(
                sheetId, artifactId, workbookId, KnowledgeNodeType.SHEET, sheetIndex,
                null, null, null, partPath, 0,
                null, null, null, null, null, null,
                sheetName, null, null, null));

        var mergedRegions = mergedRegions(poiSheet);
        var lastRow = poiSheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            var poiRow = poiSheet.getRow(r);
            if (poiRow == null) {
                continue;
            }
            var rowId = UUID.randomUUID();
            nodes.add(new KnowledgeSourceNode(
                    rowId, artifactId, sheetId, KnowledgeNodeType.SPREADSHEET_ROW, r,
                    null, null, null, partPath, r,
                    null, null, r, null, null, null,
                    sheetName, null, null, null));

            var cellLimit = poiRow.getLastCellNum();
            for (int c = 0; c < cellLimit; c++) {
                var poiCell = poiRow.getCell(c);
                if (poiCell == null) {
                    continue;
                }
                var text = formatter.formatCellValue(poiCell);
                if (isString(poiCell)) {
                    stringCells.add(text);
                }
                nodes.add(new KnowledgeSourceNode(
                        UUID.randomUUID(), artifactId, rowId,
                        KnowledgeNodeType.SPREADSHEET_CELL, c,
                        text == null || text.isBlank() ? null : text,
                        null, null, partPath, r,
                        null, null, r, c, null, null,
                        sheetName, c, coveringRegion(mergedRegions, r, c), null));
            }
        }
    }

    /** String cells, including formulas whose cached result is a string (coverage compares these). */
    private static boolean isString(org.apache.poi.ss.usermodel.Cell cell) {
        var type = cell.getCellType();
        return type == org.apache.poi.ss.usermodel.CellType.STRING
                || (type == org.apache.poi.ss.usermodel.CellType.FORMULA
                        && cell.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.STRING);
    }

    private static String partPath(XSSFSheet sheet) {
        var name = sheet.getPackagePart().getPartName().getName();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private List<MergedCellRegion> mergedRegions(XSSFSheet poiSheet) {
        var regions = new ArrayList<MergedCellRegion>();
        for (CellRangeAddress address : poiSheet.getMergedRegions()) {
            regions.add(new MergedCellRegion(
                    address.getFirstRow(), address.getLastRow(),
                    address.getFirstColumn(), address.getLastColumn()));
        }
        return List.copyOf(regions);
    }

    private static MergedCellRegion coveringRegion(
            List<MergedCellRegion> regions, int row, int column) {
        for (MergedCellRegion region : regions) {
            if (region.covers(row, column)) {
                return region;
            }
        }
        return null;
    }

    private void boundedScan(byte[] bytes) throws IOException {
        long total = 0;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                total += drain(zip);
                if (total > properties.maxUncompressedBytes()) {
                    throw new IOException("Workbook expands beyond the maximum supported size");
                }
            }
        }
        if (total == 0) {
            throw new IOException("The workbook package is empty");
        }
    }

    private static long drain(java.util.zip.ZipInputStream stream) throws IOException {
        long count = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            count += read;
        }
        return count;
    }
}
