package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.ParseCoverage.Gap;
import com.rivell.workbench.contracts.ParseCoverage.Location;
import com.rivell.workbench.contracts.SourceSpan;

/**
 * The coverage check reads the raw file on its own and reports exactly the text the parsed
 * structure does not hold. Fixture: generate_coverage_fixtures.py.
 */
class ParseCoverageTest {

    private static final String FIXTURE = "fixtures/coverage-mixed-parts.docx";

    private final DocxStructuralParser parser = new DocxStructuralParser(
            new DocxParserProperties(200L * 1024 * 1024));

    @Test
    void theRawReadSeesEveryStoryOnceInTheAcceptAllView() throws Exception {
        var segments = DocxTextInventory.read(bytes(FIXTURE));

        assertThat(segments).extracting(DocxTextInventory.Segment::text)
                .contains("Payment is due within thirty days of invoice.",
                        "Late amounts follow clause 4.2 of this Agreement.",
                        "See page 7 for details.")
                .noneMatch(text -> text.contains("sixty") || text.contains("PAGEREF")
                        || text.contains("REF _Ref1"));
        assertThat(segments).filteredOn(s -> s.location() == Location.TEXT_BOX)
                .extracting(DocxTextInventory.Segment::text)
                .containsExactly("Key terms: the liability cap is twelve months of fees.");
        assertThat(segments).extracting(DocxTextInventory.Segment::location)
                .contains(Location.TABLE, Location.HEADER, Location.FOOTER, Location.FOOTNOTE,
                        Location.COMMENT);
    }

    @Test
    void theParserReadsTrackedInsertionsAndFieldResultsButNotFieldCodes() {
        var result = parse(FIXTURE);
        var texts = result.nodes().stream().map(DocumentNode::text).toList();

        assertThat(texts).contains("Payment is due within thirty days of invoice.",
                "Late amounts follow clause 4.2 of this Agreement.", "See page 7 for details.");
        assertThat(result.warnings()).contains("TRACKED_CHANGES_PRESENT",
                "HEADERS_OR_FOOTERS_NOT_REPRESENTED", "FOOTNOTES_NOT_REPRESENTED");
    }

    @Test
    void coverageListsTheTextBoxAndTheUnrepresentedPartsAndNothingElse() {
        var coverage = parse(FIXTURE).coverage();

        assertThat(coverage.exact()).isTrue();
        assertThat(coverage.extraWords()).isZero();
        assertThat(coverage.gaps()).extracting(Gap::location).containsExactly(
                Location.TEXT_BOX, Location.HEADER, Location.FOOTER, Location.FOOTNOTE,
                Location.COMMENT);
        assertThat(coverage.gaps().get(0).words()).isEqualTo(10);
        assertThat(coverage.gaps().get(2).excerpt()).isEqualTo("Northwind order form");
        assertThat(coverage.totals()).filteredOn(t -> t.location() == Location.BODY)
                .singleElement()
                .satisfies(t -> assertThat(t.capturedWords()).isEqualTo(t.sourceWords()));
    }

    @Test
    void wordsMissingFromTheMiddleOfAParagraphBecomeOneGapAndInventedWordsCountAsExtra() {
        var source = List.of(new DocxTextInventory.Segment(DocxTextInventory.MAIN_PART,
                Location.BODY, "The cap is twelve months of fees paid.", true));
        var nodes = nodes("The cap is fees paid.", "An invented sentence.");

        var coverage = TextCoverage.measure(source, nodes);

        assertThat(coverage.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.location()).isEqualTo(Location.BODY);
            assertThat(gap.words()).isEqualTo(3);
            assertThat(gap.excerpt()).isEqualTo("twelve months of");
        });
        assertThat(coverage.extraWords()).isEqualTo(3);
        assertThat(coverage.capturedWords()).isEqualTo(5);
    }

    @Test
    void aFileTooDifferentToAlignFallsBackToWholeParagraphMatching() {
        var source = new ArrayList<DocxTextInventory.Segment>();
        source.add(new DocxTextInventory.Segment(DocxTextInventory.MAIN_PART, Location.BODY,
                "Kept paragraph.", true));
        var lost = new StringBuilder();
        for (int i = 0; i <= TextCoverage.MAX_EDITS; i++) {
            lost.append("w").append(i).append(' ');
        }
        source.add(new DocxTextInventory.Segment(DocxTextInventory.MAIN_PART, Location.BODY,
                lost.toString(), true));

        var coverage = TextCoverage.measure(source, nodes("Kept paragraph."));

        assertThat(coverage.exact()).isFalse();
        assertThat(coverage.capturedWords()).isEqualTo(2);
        assertThat(coverage.gaps()).singleElement()
                .satisfies(gap -> assertThat(gap.words()).isEqualTo(TextCoverage.MAX_EDITS + 1));
    }

    private com.rivell.workbench.contracts.ParseResult parse(String fixture) {
        return parser.parse(new ByteArrayInputStream(bytes(fixture)), UUID.randomUUID());
    }

    private byte[] bytes(String fixture) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<DocumentNode> nodes(String... texts) {
        var version = UUID.randomUUID();
        var out = new ArrayList<DocumentNode>();
        for (int i = 0; i < texts.length; i++) {
            out.add(new DocumentNode(UUID.randomUUID(), version, null, DocumentNodeType.PARAGRAPH,
                    i, texts[i], null, null,
                    new SourceSpan(DocxTextInventory.MAIN_PART, i, i, null, null, null, null, null),
                    null));
        }
        return out;
    }
}
