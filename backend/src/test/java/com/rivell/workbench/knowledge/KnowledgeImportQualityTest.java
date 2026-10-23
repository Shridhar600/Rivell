package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.platform.parsing.DocxKnowledgeExtractor;
import com.rivell.workbench.platform.parsing.DocxParserProperties;
import com.rivell.workbench.platform.parsing.DocxStructuralParser;
import com.rivell.workbench.platform.parsing.XlsxKnowledgeExtractor;

/**
 * Deterministic import quality on two real user files (fictional dev data, copied from the
 * usertest corpus): a Word negotiation guide and an Excel clause library. No model runs here;
 * what a lawyer reviews starts as what the compiler makes of the source.
 */
class KnowledgeImportQualityTest {

    private static final String GUIDE = "fixtures/import-quality-nda-guide.docx";
    private static final String LIBRARY = "fixtures/import-quality-clause-library.xlsx";
    private static final String MASTHEAD = "LINDENWAKE ROUTE SYSTEMS | INTERNAL GUIDANCE";
    private static final DocxParserProperties PARSER_LIMITS =
            new DocxParserProperties(200L * 1024 * 1024);

    @Test
    void everyEntryTitleIsShortAndOnOneLine() {
        var compiled = compileGuide();

        assertThat(compiled.candidates()).isNotEmpty();
        assertThat(compiled.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.rawLabel()).doesNotContain("\n");
            assertThat(candidate.rawLabel().length()).isLessThanOrEqualTo(KnowledgeTitles.MAX_LENGTH);
        });
    }

    @Test
    void aCalloutBoxIsTitledByItsOwnLeadLineAndKeepsItsTextAsWording() {
        var compiled = compileGuide();

        var callout = compiled.candidates().stream()
                .filter(candidate -> "DO NOT TRADE THIS BOUNDARY FOR SPEED".equals(candidate.rawLabel()))
                .findFirst().orElseThrow();
        assertThat(callout.contentText()).startsWith("No live or identifiable fleet data");
        assertThat(callout.contentText()).doesNotContain("DO NOT TRADE");
    }

    @Test
    void noPlaceholderColumnNameReachesAnEntry() {
        var compiled = compileGuide();

        assertThat(compiled.candidates()).allSatisfy(candidate ->
                assertThat(candidate.payload().columns().keySet())
                        .noneMatch(name -> name.matches("Column \\d+") || name.contains("\n")));
    }

    @Test
    void aLabelAndValueInOneCellBecomeALabelledField() {
        var compiled = compileGuide();

        var details = compiled.candidates().stream()
                .filter(candidate -> candidate.payload().columns().containsKey("OWNER"))
                .findFirst().orElseThrow();
        assertThat(details.payload().columns())
                .containsEntry("OWNER", "Director, Commercial Legal")
                .containsEntry("VERSION", "1.0")
                .containsEntry("APPROVAL", "General Counsel");
    }

    @Test
    void thePageHeaderIsNotRepeatedAsAUnitTitle() {
        var compiled = compileGuide();

        assertThat(compiled.units()).noneMatch(unit -> MASTHEAD.equals(unit.title()));
        assertThat(compiled.candidates()).noneMatch(candidate -> MASTHEAD.equals(candidate.rawLabel()));
    }

    @Test
    void tableRowsUnderOneHeadingHaveTitlesThatTellThemApart() {
        var compiled = compileGuide();

        var approvalRows = compiled.units().stream()
                .filter(unit -> unit.kind() == ExtractionUnitKind.TABLE
                        && unit.title() != null && unit.title().startsWith("Approval path"))
                .toList();
        assertThat(approvalRows).hasSize(5);
        assertThat(approvalRows.stream().map(ExtractionUnit::title).distinct()).hasSize(5);
    }

    @Test
    void aClauseVariantsWordingIsItsClauseTextColumnNotTheWholeRow() {
        var compiled = compileLibrary();

        var fallback = compiled.candidates().stream()
                .filter(candidate -> "G-IND-01".equals(candidate.rawLabel())
                        && "Fallback 1".equals(candidate.payload().columns().get("Variant")))
                .findFirst().orElseThrow();
        assertThat(fallback.contentText())
                .isEqualTo(fallback.payload().columns().get("Clause text"))
                .startsWith("The Supplier shall defend and indemnify the Customer")
                .doesNotContain(" | ");
        assertThat(fallback.payload().columns())
                .containsEntry("Clause ID", "G-IND-01")
                .containsEntry("Guidance note", "Acceptable only if the cap itself meets G-LIAB-01.");
    }

    @Test
    void theDocumentTitleIsReadPastTheMasthead() {
        var nodes = new DocxKnowledgeExtractor(new DocxStructuralParser(PARSER_LIMITS))
                .extractStructure(UUID.randomUUID(), stream(GUIDE)).nodes();

        assertThat(KnowledgeDocumentTitle.of(nodes)).isEqualTo("Mutual NDA Negotiation Guide");
        assertThat(compileGuide().units().getFirst().title()).isEqualTo("Mutual NDA Negotiation Guide");
    }

    @Test
    void aWorkbookHasNoDocumentTitle() {
        var nodes = new XlsxKnowledgeExtractor(PARSER_LIMITS)
                .extractStructure(UUID.randomUUID(), stream(LIBRARY)).nodes();

        assertThat(KnowledgeDocumentTitle.of(nodes)).isNull();
    }

    private KnowledgeCompiler.CompiledKnowledge compileGuide() {
        return compile(GUIDE, true, ArtifactType.PLAYBOOK);
    }

    private KnowledgeCompiler.CompiledKnowledge compileLibrary() {
        return compile(LIBRARY, false, ArtifactType.CLAUSE_LIBRARY);
    }

    private KnowledgeCompiler.CompiledKnowledge compile(String fixture, boolean docx, ArtifactType type) {
        var id = UUID.randomUUID();
        var extraction = docx
                ? new DocxKnowledgeExtractor(new DocxStructuralParser(PARSER_LIMITS))
                        .extractStructure(id, stream(fixture))
                : new XlsxKnowledgeExtractor(PARSER_LIMITS).extractStructure(id, stream(fixture));
        var manifest = new ArtifactManifest(Set.of("NDA"), null, null, null, null, null, null, null, null);
        var artifact = new KnowledgeArtifact(id, UUID.randomUUID(), type,
                docx ? "guide.docx" : "library.xlsx", "application/octet-stream", 1, "sha", "key",
                manifest, IngestStatus.EXTRACTED, null, List.of(), null, Instant.now(), null);
        return KnowledgeCompiler.compile(artifact, extraction.nodes());
    }

    private InputStream stream(String fixture) {
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(source).as("classpath resource %s", fixture).isNotNull();
            return new ByteArrayInputStream(source.readAllBytes());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
