package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTMarkup;
import org.docx4j.wml.DelText;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

import com.rivell.workbench.platform.parsing.RedlineFixturePipeline;
import com.rivell.workbench.platform.docx.DocxRedlineRenderer;

class DocxRedlineRendererTest {

    /**
     * A deliberately small expansion budget so the bomb test is fast and deterministic. Production
     * uses the configured 200 MB ceiling; the property is the same one the ingest parser honours.
     */
    private static final long MAX_UNCOMPRESSED_BYTES = 64 * 1024;

    private final DocxRedlineRenderer renderer = new DocxRedlineRenderer(
            new com.rivell.workbench.platform.parsing.DocxParserProperties(
                    MAX_UNCOMPRESSED_BYTES));

    @Test
    void simpleReplaceProducesTrackedDeletionAndInsertionAndKeepsTheHeading() {
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");

        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-simple-replace.docx"), plan);
        var wordPackage = RedlineFixturePipeline.load(rendered.bytes());
        var paragraphs = paragraphs(wordPackage);

        var heading = paragraphWithText(paragraphs, "2. Limitation of Liability");
        assertThat(isDeleted(heading)).isFalse();
        assertThat(isInserted(heading)).isFalse();

        var replaced = paragraphWithText(paragraphs, "Supplier liability shall be unlimited.");
        assertThat(isDeleted(replaced)).isTrue();
        assertThat(deletedText(replaced)).isEqualTo("Supplier liability shall be unlimited.");
        assertThat(runDelCount(replaced)).isEqualTo(1);

        var inserted = paragraphWithText(paragraphs,
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        assertThat(isInserted(inserted)).isTrue();
        assertThat(insertedText(inserted))
                .isEqualTo("Aggregate liability shall not exceed the fees paid in the prior months.");
        assertThat(runInsCount(inserted)).isEqualTo(1);

        assertThat(paragraphs.indexOf(inserted)).isGreaterThan(paragraphs.indexOf(heading));
        assertThat(paragraphs.indexOf(inserted)).isLessThan(paragraphs.indexOf(replaced));
    }

    @Test
    void fragmentedRunsAreDeletedWithExactText() {
        var plan = replacePlan("m9-fragmented-run.docx", "2. Limitation of Liability",
                "Liability is capped at the fees paid.");

        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-fragmented-run.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        var replaced = paragraphWithText(paragraphs, "Supplier liability shall be unlimited.");
        assertThat(runDelCount(replaced)).isEqualTo(5);
        assertThat(deletedText(replaced)).isEqualTo("Supplier liability shall be unlimited.");
    }

    @Test
    void deleteRemovesTheWholeClauseIncludingTheHeading() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse("m9-delete.docx", contractId, versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.delete(setId, 0,
                RedlineFixturePipeline.headingId(structure, "2. Non-Solicitation"));
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));

        var rendered = renderer.render(RedlineFixturePipeline.fixture("m9-delete.docx"),
                RedlineFixturePipeline.plan(structure, set, Map.of()));
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        assertThat(isDeleted(paragraphWithText(paragraphs, "2. Non-Solicitation"))).isTrue();
        assertThat(isDeleted(paragraphWithText(paragraphs,
                "Neither party shall solicit the employees of the other party."))).isTrue();
        assertThat(isDeleted(paragraphWithText(paragraphs,
                "This restriction survives for twelve months after termination."))).isTrue();
        assertThat(allRunIns(paragraphs)).isEmpty();
    }

    @Test
    void insertPlacesApprovedLanguageAfterTheDeterministicAnchor() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse("m9-insert.docx", contractId, versionId);
        var concepts = RedlineFixturePipeline.concepts(structure, Map.of(
                "2. Indemnification", CoreConcepts.INDEMNIFICATION,
                "3. Confidentiality", CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                "4. Term and Survival", CoreConcepts.TERM,
                "5. Governing Law", CoreConcepts.GOVERNING_LAW));
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.insert(setId, 0,
                CoreConcepts.LIMITATION_OF_LIABILITY,
                "Aggregate liability shall not exceed the fees paid.");
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));

        var plan = RedlineFixturePipeline.plan(structure, set, concepts);
        assertThat(plan.operations().get(0).placementLabel())
                .isEqualTo("AFTER_CLAUSE[INDEMNIFICATION]");

        var rendered = renderer.render(RedlineFixturePipeline.fixture("m9-insert.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        var inserted = paragraphWithText(paragraphs,
                "Aggregate liability shall not exceed the fees paid.");
        assertThat(isInserted(inserted)).isTrue();
        assertThat(insertedText(inserted))
                .isEqualTo("Aggregate liability shall not exceed the fees paid.");
        var indemnification = paragraphWithText(paragraphs,
                "Each party shall indemnify the other party against third-party claims.");
        assertThat(paragraphs.indexOf(inserted))
                .isEqualTo(paragraphs.indexOf(indemnification) + 1);
        assertThat(allRunDels(paragraphs)).isEmpty();
    }

    @Test
    void multipleChangesApplyIndependentlyWithoutTargetDrift() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse("m9-multi-change.docx", contractId,
                versionId);
        var concepts = RedlineFixturePipeline.concepts(structure, Map.of(
                "1. Limitation of Liability", CoreConcepts.LIMITATION_OF_LIABILITY,
                "3. Indemnification", CoreConcepts.INDEMNIFICATION,
                "4. Confidentiality", CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                "5. Governing Law", CoreConcepts.GOVERNING_LAW));
        var setId = UUID.randomUUID();
        var replace = RedlineTestStructures.replace(setId, 0,
                RedlineFixturePipeline.headingId(structure, "1. Limitation of Liability"),
                "Liability is capped at the fees paid in the prior twelve months.");
        var delete = RedlineTestStructures.delete(setId, 1,
                RedlineFixturePipeline.headingId(structure, "2. Non-Solicitation"));
        var insert = RedlineTestStructures.insert(setId, 2, CoreConcepts.TERM,
                "Either party may terminate for material breach after thirty days notice.");
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(replace, delete, insert));

        var rendered = renderer.render(RedlineFixturePipeline.fixture("m9-multi-change.docx"),
                RedlineFixturePipeline.plan(structure, set, concepts));
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        assertThat(deletedText(paragraphWithText(paragraphs,
                "Supplier liability shall be unlimited.")))
                .isEqualTo("Supplier liability shall be unlimited.");
        assertThat(isDeleted(paragraphWithText(paragraphs, "2. Non-Solicitation"))).isTrue();
        var inserted = paragraphWithText(paragraphs,
                "Either party may terminate for material breach after thirty days notice.");
        assertThat(isInserted(inserted)).isTrue();
        var confidentiality = paragraphWithText(paragraphs,
                "Each party shall keep the other party information confidential.");
        assertThat(paragraphs.indexOf(inserted))
                .isEqualTo(paragraphs.indexOf(confidentiality) + 1);
    }

    @Test
    void multiParagraphLanguageBecomesMultipleInsertedParagraphs() {
        var plan = replacePlan("m9-multi-paragraph.docx", "1. Limitation of Liability",
                "First replacement line.\nSecond replacement line.\n\nFourth replacement line.");

        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-multi-paragraph.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        assertThat(isInserted(paragraphWithText(paragraphs, "First replacement line."))).isTrue();
        assertThat(isInserted(paragraphWithText(paragraphs, "Second replacement line."))).isTrue();
        assertThat(isInserted(paragraphWithText(paragraphs, ""))).isTrue();
        assertThat(isInserted(paragraphWithText(paragraphs, "Fourth replacement line."))).isTrue();
        assertThat(insertedText(paragraphWithText(paragraphs, ""))).isEmpty();
    }

    @Test
    void numberedClauseReplacementLeavesNumberingPartsUntouched() throws Exception {
        var fixture = "m9-numbered.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Liability is capped at the fees paid.");

        var rendered = renderer.render(RedlineFixturePipeline.fixture(fixture), plan);
        var parts = zipEntries(rendered.bytes());
        var sourceParts = zipEntries(RedlineFixturePipeline.fixture(fixture));

        assertThat(parts.get("word/numbering.xml"))
                .isEqualTo(sourceParts.get("word/numbering.xml"));
        assertThat(parts.get("word/styles.xml")).isEqualTo(sourceParts.get("word/styles.xml"));
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));
        assertThat(isDeleted(paragraphWithText(paragraphs,
                "Supplier liability shall be unlimited."))).isTrue();
    }

    @Test
    void deletingTheFinalClauseFailsClosed() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse("m9-simple-replace.docx", contractId,
                versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.delete(setId, 0,
                RedlineFixturePipeline.headingId(structure, "4. Governing Law"));
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));

        assertThatThrownBy(() -> RedlineFixturePipeline.plan(structure, set, Map.of()))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE);
    }

    @Test
    void paragraphCarryingSectionPropertiesFailsClosed() {
        var plan = replacePlan("m9-sectpr-paragraph.docx", "1. Services",
                "Replacement services language.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-sectpr-paragraph.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE);
    }

    @Test
    void paragraphMarkAndRunWrappersShareOneRevisionIdPerParagraph() {
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-simple-replace.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        var deleted = paragraphWithText(paragraphs, "Supplier liability shall be unlimited.");
        assertThat(deleted.getPPr().getRPr().getDel().getId().longValue())
                .isEqualTo(deletedElements(deleted).get(0).getId().longValue());
        var inserted = paragraphWithText(paragraphs, "Liability is capped at the fees paid.");
        assertThat(inserted.getPPr().getRPr().getIns().getId().longValue())
                .isEqualTo(allRunIns(List.of(inserted)).get(0).getId().longValue());
    }

    @Test
    void tableBodyTargetsFailClosedAtPlanTime() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse("m9-table-target.docx", contractId,
                versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.replace(setId, 0,
                RedlineFixturePipeline.headingId(structure, "1. Pricing"), "New pricing text.");
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));

        assertThatThrownBy(() -> RedlineFixturePipeline.plan(structure, set, Map.of()))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE);
    }

    @Test
    void hyperlinkInsideTargetIsDeletedWithinTheHyperlink() {
        var plan = replacePlan("m9-hyperlink-target.docx", "1. Limitation of Liability",
                "Liability is capped at the fees paid.");

        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-hyperlink-target.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));

        var replaced = paragraphWithText(paragraphs,
                "Supplier liability is limited as described at the liability page.");
        assertThat(isDeleted(replaced)).isTrue();
        assertThat(deletedText(replaced))
                .isEqualTo("Supplier liability is limited as described at the liability page.");
        assertThat(replaced.getContent().stream()
                .map(org.docx4j.XmlUtils::unwrap)
                .anyMatch(P.Hyperlink.class::isInstance)).isTrue();
    }

    @Test
    void preexistingRevisionsFailClosed() {
        var plan = replacePlan("m9-preexisting-revisions.docx", "1. Limitation of Liability",
                "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-preexisting-revisions.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.PREEXISTING_REVISIONS_UNSUPPORTED);
    }

    @Test
    void propertyMarkRevisionsFailClosed() {
        var plan = replacePlan("m9-preexisting-property-revisions.docx",
                "1. Limitation of Liability", "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-preexisting-property-revisions.docx"),
                plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.PREEXISTING_REVISIONS_UNSUPPORTED);
    }

    @Test
    void hiddenPropertyRevisionsFailClosed() {
        var plan = replacePlan("m9-preexisting-hidden-revisions.docx",
                "1. Limitation of Liability", "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-preexisting-hidden-revisions.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.PREEXISTING_REVISIONS_UNSUPPORTED);
    }

    /**
     * The redline path bounds decompression of stored source bytes, as the ingest parser
     * does. A package that expands far beyond its stored size must
     * fail closed at the redline boundary with its own reason, not be handed to docx4j.
     */
    @Test
    void aPackageThatExpandsBeyondThePermittedSizeFailsClosed() {
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var bomb = decompressionBomb("m9-simple-replace.docx");

        assertThatThrownBy(() -> renderer.render(bomb, plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.SOURCE_EXPANSION_LIMIT_EXCEEDED);
    }

    /**
     * Builds a package that stores a small file but declares a far larger uncompressed payload: the
     * shape of a decompression bomb, without needing a large fixture on disk.
     */
    private static byte[] decompressionBomb(String fixture) {
        var original = RedlineFixturePipeline.fixture(fixture);
        var inflated = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(original))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                inflated.write(zip.readAllBytes());
            }
        } catch (Exception e) {
            throw new IllegalStateException("fixture could not be expanded", e);
        }
        var payload = new byte[(int) (MAX_UNCOMPRESSED_BYTES * 4)];
        java.util.Arrays.fill(payload, (byte) ' ');

        var bomb = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.ZipOutputStream(bomb)) {
            out.putNextEntry(new ZipEntry("word/document.xml"));
            out.write(payload);
            out.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException("bomb could not be built", e);
        }
        return bomb.toByteArray();
    }

    @Test
    void protectedDocumentFailsClosed() {
        var plan = replacePlan("m9-protected.docx", "1. Limitation of Liability",
                "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-protected.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.PROTECTED_DOCUMENT_UNSUPPORTED);
    }

    @Test
    void macroEnabledPackageFailsClosed() {
        var plan = replacePlan("m9-macro.docx", "1. Limitation of Liability",
                "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("m9-macro.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_FORMAT);
    }

    @Test
    void corruptDocumentFailsClosed() {
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                "Liability is capped at the fees paid.");

        assertThatThrownBy(() -> renderer.render(
                RedlineFixturePipeline.fixture("invalid-bytes.docx"), plan))
                .isInstanceOf(RedlineRenderException.class)
                .extracting(e -> ((RedlineRenderException) e).code())
                .isEqualTo(RedlineFailureCode.DOCX_LOAD_FAILED);
    }

    @Test
    void unmodifiedPartsArePreservedByteForByte() throws Exception {
        var fixture = "m9-with-parts.docx";
        var plan = replacePlan(fixture, "1. Limitation of Liability",
                "Liability is capped at the fees paid.");

        var rendered = renderer.render(RedlineFixturePipeline.fixture(fixture), plan);
        var generated = zipEntries(rendered.bytes());
        var source = zipEntries(RedlineFixturePipeline.fixture(fixture));

        assertThat(generated.get("word/header1.xml")).isEqualTo(source.get("word/header1.xml"));
        assertThat(generated.get("word/footer1.xml")).isEqualTo(source.get("word/footer1.xml"));
        assertThat(generated.get("word/styles.xml")).isEqualTo(source.get("word/styles.xml"));
        assertThat(generated.get("word/media/image1.png"))
                .isEqualTo(source.get("word/media/image1.png"));
        assertThat(generated.get("word/document.xml")).isNotEqualTo(source.get("word/document.xml"));
        assertThat(generated.keySet()).isEqualTo(source.keySet());
    }

    @Test
    void renderIsDeterministicForTheSamePlanAndExecutionTime() {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var bytes = RedlineFixturePipeline.fixture(fixture);

        var first = renderer.render(bytes, plan);
        var second = renderer.render(bytes, plan);

        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(first.bytes()).isEqualTo(second.bytes());
    }

    @Test
    void revisionIdsAreUniqueAndReportedPerChange() {
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-simple-replace.docx"), plan);

        var ids = new ArrayList<Long>();
        for (var markup : markupElements(RedlineFixturePipeline.load(rendered.bytes()))) {
            if (markup.getId() != null) {
                ids.add(markup.getId().longValue());
            }
        }
        assertThat(ids).doesNotHaveDuplicates();
        var report = rendered.changes().get(0);
        assertThat(ids).allSatisfy(id -> assertThat(id)
                .isBetween(report.revisionIdStart(), report.revisionIdEnd()));
        assertThat(report.paragraphsDeleted()).isEqualTo(1);
        assertThat(report.paragraphsInserted()).isEqualTo(1);
    }

    @Test
    void leadingAndTrailingSpacesArePreservedExactly() {
        var language = "  spaced approved language  ";
        var plan = replacePlan("m9-simple-replace.docx", "2. Limitation of Liability", language);

        var rendered = renderer.render(
                RedlineFixturePipeline.fixture("m9-simple-replace.docx"), plan);
        var paragraphs = paragraphs(RedlineFixturePipeline.load(rendered.bytes()));
        var inserted = paragraphWithText(paragraphs, language);

        assertThat(insertedText(inserted)).isEqualTo(language);
        var textElement = firstInsertedTextElement(inserted);
        assertThat(textElement.getSpace()).isEqualTo("preserve");
    }

    private DocumentExecutionPlan replacePlan(String fixture, String headingText,
            String language) {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.replace(setId, 0,
                RedlineFixturePipeline.headingId(structure, headingText), language);
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));
        return RedlineFixturePipeline.plan(structure, set, Map.of());
    }

    private static List<P> paragraphs(WordprocessingMLPackage wordPackage) {
        var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
        var paragraphs = new ArrayList<P>();
        for (var raw : body.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof P paragraph) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private static P paragraphWithText(List<P> paragraphs, String text) {
        return paragraphs.stream()
                .filter(paragraph -> text.equals(visibleText(paragraph)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no paragraph with text: " + text
                        + " in " + paragraphs.stream().map(DocxRedlineRendererTest::visibleText)
                                .toList()));
    }

    private static String visibleText(P paragraph) {
        var text = new StringBuilder();
        collectText(paragraph, text);
        return text.toString();
    }

    private static void collectText(Object element, StringBuilder text) {
        var value = org.docx4j.XmlUtils.unwrap(element);
        if (value instanceof P paragraph) {
            for (var child : paragraph.getContent()) {
                collectText(child, text);
            }
        } else if (value instanceof R run) {
            for (var child : run.getContent()) {
                collectText(child, text);
            }
        } else if (value instanceof P.Hyperlink hyperlink) {
            for (var child : hyperlink.getContent()) {
                collectText(child, text);
            }
        } else if (value instanceof Text textElement) {
            text.append(textElement.getValue());
        } else if (value instanceof DelText delText) {
            text.append(delText.getValue());
        } else if (value instanceof RunIns ins) {
            for (var child : ins.getCustomXmlOrSmartTagOrSdt()) {
                collectText(child, text);
            }
        } else if (value instanceof RunDel del) {
            for (var child : del.getCustomXmlOrSmartTagOrSdt()) {
                collectText(child, text);
            }
        }
    }

    private static boolean isDeleted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getDel() != null;
    }

    private static boolean isInserted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getIns() != null;
    }

    private static String deletedText(P paragraph) {
        var text = new StringBuilder();
        for (var element : deletedElements(paragraph)) {
            if (element instanceof RunDel del) {
                for (var child : del.getCustomXmlOrSmartTagOrSdt()) {
                    collectDelText(child, text);
                }
            }
        }
        return text.toString();
    }

    private static void collectDelText(Object element, StringBuilder text) {
        var value = org.docx4j.XmlUtils.unwrap(element);
        if (value instanceof R run) {
            for (var child : run.getContent()) {
                collectDelText(child, text);
            }
        } else if (value instanceof P.Hyperlink hyperlink) {
            for (var child : hyperlink.getContent()) {
                collectDelText(child, text);
            }
        } else if (value instanceof DelText delText) {
            text.append(delText.getValue());
        }
    }

    private static String insertedText(P paragraph) {
        var text = new StringBuilder();
        for (var element : allRunIns(List.of(paragraph))) {
            for (var child : element.getCustomXmlOrSmartTagOrSdt()) {
                collectInsText(child, text);
            }
        }
        return text.toString();
    }

    private static void collectInsText(Object element, StringBuilder text) {
        var value = org.docx4j.XmlUtils.unwrap(element);
        if (value instanceof R run) {
            for (var child : run.getContent()) {
                collectInsText(child, text);
            }
        } else if (value instanceof Text textElement) {
            text.append(textElement.getValue());
        }
    }

    private static Text firstInsertedTextElement(P paragraph) {
        for (var ins : allRunIns(List.of(paragraph))) {
            for (var child : ins.getCustomXmlOrSmartTagOrSdt()) {
                var value = org.docx4j.XmlUtils.unwrap(child);
                if (value instanceof R run) {
                    for (var runChild : run.getContent()) {
                        var runValue = org.docx4j.XmlUtils.unwrap(runChild);
                        if (runValue instanceof Text text) {
                            return text;
                        }
                    }
                }
            }
        }
        throw new AssertionError("no inserted text element");
    }

    private static List<RunIns> allRunIns(List<P> paragraphs) {
        var elements = new ArrayList<RunIns>();
        for (var paragraph : paragraphs) {
            for (var raw : paragraph.getContent()) {
                var element = org.docx4j.XmlUtils.unwrap(raw);
                if (element instanceof RunIns ins) {
                    elements.add(ins);
                }
            }
        }
        return elements;
    }

    private static List<RunDel> allRunDels(List<P> paragraphs) {
        var elements = new ArrayList<RunDel>();
        for (var paragraph : paragraphs) {
            elements.addAll(deletedElements(paragraph));
        }
        return elements;
    }

    private static List<RunDel> deletedElements(P paragraph) {
        var elements = new ArrayList<RunDel>();
        for (var raw : paragraph.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof RunDel del) {
                elements.add(del);
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = org.docx4j.XmlUtils.unwrap(rawLinkChild);
                    if (linkChild instanceof RunDel del) {
                        elements.add(del);
                    }
                }
            }
        }
        return elements;
    }

    private static int runDelCount(P paragraph) {
        return deletedElements(paragraph).size();
    }

    private static int runInsCount(P paragraph) {
        return allRunIns(List.of(paragraph)).size();
    }

    private static List<CTMarkup> markupElements(WordprocessingMLPackage wordPackage) {
        var markup = new ArrayList<CTMarkup>();
        collectMarkup(wordPackage.getMainDocumentPart().getJaxbElement(), markup);
        return markup;
    }

    private static void collectMarkup(Object node, List<CTMarkup> markup) {
        if (node == null) {
            return;
        }
        if (node instanceof CTMarkup element) {
            markup.add(element);
        }
        var children = org.docx4j.TraversalUtil.getChildrenImpl(node);
        if (children == null) {
            return;
        }
        for (var child : children) {
            collectMarkup(child, markup);
        }
    }

    private static Map<String, byte[]> zipEntries(byte[] bytes) throws Exception {
        var entries = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
