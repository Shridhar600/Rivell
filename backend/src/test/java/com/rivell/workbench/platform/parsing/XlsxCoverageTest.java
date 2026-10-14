package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ParseCoverage.Gap;
import com.rivell.workbench.contracts.ParseCoverage.Location;

/**
 * Knowledge workbooks: the raw read sees every text cell in workbook order plus comments and
 * drawing text, and coverage reports exactly what the extracted structure does not hold.
 * Fixture: generate_coverage_fixtures.py (sheet order in the workbook differs from file order).
 */
class XlsxCoverageTest {

    private static final String FIXTURE = "fixtures/coverage-workbook.xlsx";

    @Test
    void theRawReadFollowsWorkbookOrderAndKeepsOnlyTextCells() throws Exception {
        var segments = XlsxTextInventory.read(bytes());

        assertThat(segments).filteredOn(DocxTextInventory.Segment::aligned)
                .extracting(DocxTextInventory.Segment::text)
                .containsExactly("Topic", "Preferred position", "Limitation of liability",
                        "Cap at twelve months of fees", "Use the latest approved template.");
        assertThat(segments).filteredOn(segment -> !segment.aligned())
                .extracting(DocxTextInventory.Segment::location, DocxTextInventory.Segment::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Location.COMMENT,
                                "Board approval needed above this cap."),
                        org.assertj.core.groups.Tuple.tuple(Location.TEXT_BOX,
                                "Escalate every walk-away to the General Counsel."));
    }

    @Test
    void extractionReportsCommentsAndTextBoxesAsTextTheStructureDoesNotHold() {
        var extraction = new XlsxKnowledgeExtractor(new DocxParserProperties(200L * 1024 * 1024))
                .extractStructure(UUID.randomUUID(), new ByteArrayInputStream(bytes()));
        var coverage = extraction.coverage();

        assertThat(coverage.exact()).isTrue();
        assertThat(coverage.extraWords()).isZero();
        assertThat(coverage.totals()).filteredOn(total -> total.location() == Location.TABLE)
                .singleElement()
                .satisfies(total -> assertThat(total.capturedWords()).isEqualTo(total.sourceWords()));
        assertThat(coverage.gaps()).extracting(Gap::location)
                .containsExactly(Location.COMMENT, Location.TEXT_BOX);
    }

    private byte[] bytes() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("classpath resource %s", FIXTURE).isNotNull();
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
