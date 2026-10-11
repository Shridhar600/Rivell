package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.DelText;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

import com.rivell.workbench.platform.parsing.RedlineFixturePipeline;
import com.rivell.workbench.platform.docx.DocxRedlineRenderer;
import com.rivell.workbench.platform.docx.DocxRedlineVerifier;

class DocxRedlineVerifierTest {

    private final DocxRedlineRenderer renderer = new DocxRedlineRenderer(new com.rivell.workbench.platform.parsing.DocxParserProperties(
                200L * 1024 * 1024));
    private final DocxRedlineVerifier verifier = new DocxRedlineVerifier(new com.rivell.workbench.platform.parsing.DocxParserProperties(
                200L * 1024 * 1024));

    @Test
    void simpleReplaceRoundTripsAndVerifiesEveryChange() {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
        assertThat(report.changes()).hasSize(1);
        assertThat(report.changes().get(0).status())
                .isEqualTo(RedlineVerificationStatus.VERIFIED);
    }

    @Test
    void multiChangeExecutionRoundTrips() {
        var fixture = "m9-multi-change.docx";
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
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
        var plan = RedlineFixturePipeline.plan(structure, set, concepts);
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
        assertThat(report.changes()).hasSize(3);
        assertThat(report.changes())
                .allSatisfy(change -> assertThat(change.status())
                        .isEqualTo(RedlineVerificationStatus.VERIFIED));
    }

    @Test
    void fragmentedRunReplaceRoundTrips() {
        var fixture = "m9-fragmented-run.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
    }

    @Test
    void sdtNestedClauseDeleteRoundTrips() {
        var fixture = "m9-sdt-clause.docx";
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.delete(setId, 0,
                RedlineFixturePipeline.headingId(structure, "1. Services"));
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));
        var plan = RedlineFixturePipeline.plan(structure, set, Map.of());
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
    }

    @Test
    void deletedTextInsideAnInsertedRunIsRejected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));

        for (var paragraph : paragraphs(wordPackage)) {
            var pPr = paragraph.getPPr();
            if (pPr == null || pPr.getRPr() == null || pPr.getRPr().getIns() == null) {
                continue;
            }
            for (var raw : paragraph.getContent()) {
                var element = org.docx4j.XmlUtils.unwrap(raw);
                if (element instanceof RunIns ins) {
                    for (var wrapped : ins.getCustomXmlOrSmartTagOrSdt()) {
                        var run = org.docx4j.XmlUtils.unwrap(wrapped);
                        if (run instanceof R inner) {
                            for (var rawRunChild : inner.getContent()) {
                                var runChild = org.docx4j.XmlUtils.unwrap(rawRunChild);
                                if (runChild instanceof Text text) {
                                    var deleted = new DelText();
                                    deleted.setValue(text.getValue());
                                    inner.getContent().set(
                                            inner.getContent().indexOf(rawRunChild), deleted);
                                }
                            }
                        }
                    }
                }
            }
        }
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan,
                rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void verifierComparesAgainstThePlanNotTheRendererReport() {
        var fixture = "m9-simple-replace.docx";
        var source = RedlineFixturePipeline.fixture(fixture);
        var honestPlan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var differentPlan = replacePlan(fixture, "2. Limitation of Liability",
                "A completely different approved replacement.");

        var rendered = renderer.render(source, honestPlan);
        var report = verifier.verify(source, rendered.bytes(), differentPlan, rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void tamperedDeletedTextIsDetected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);

        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));
        var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
        for (var raw : body.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof P paragraph) {
                for (var rawChild : paragraph.getContent()) {
                    var child = org.docx4j.XmlUtils.unwrap(rawChild);
                    if (child instanceof RunDel del) {
                        for (var rawWrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                            var wrapped = org.docx4j.XmlUtils.unwrap(rawWrapped);
                            if (wrapped instanceof R run) {
                                for (var rawRunChild : run.getContent()) {
                                    var runChild = org.docx4j.XmlUtils.unwrap(rawRunChild);
                                    if (runChild instanceof DelText delText) {
                                        delText.setValue("tampered deletion text");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan, rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void missingSourcePartIsDetected() throws Exception {
        var fixture = "m9-with-parts.docx";
        var plan = replacePlan(fixture, "1. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);

        var stripped = stripPart(rendered.bytes(), "word/header1.xml");
        var report = verifier.verify(source, stripped, plan, rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void unwrappedDeletionRunsAreRejected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));

        for (var paragraph : paragraphs(wordPackage)) {
            var pPr = paragraph.getPPr();
            if (pPr == null || pPr.getRPr() == null || pPr.getRPr().getDel() == null) {
                continue;
            }
            var unwrapped = new ArrayList<Object>();
            for (var raw : paragraph.getContent()) {
                var element = org.docx4j.XmlUtils.unwrap(raw);
                if (element instanceof RunDel del) {
                    for (var wrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                        var run = org.docx4j.XmlUtils.unwrap(wrapped);
                        if (run instanceof R inner) {
                            for (var rawRunChild : inner.getContent()) {
                                var runChild = org.docx4j.XmlUtils.unwrap(rawRunChild);
                                if (runChild instanceof DelText delText) {
                                    var text = new org.docx4j.wml.Text();
                                    text.setValue(delText.getValue());
                                    text.setSpace(delText.getSpace());
                                    inner.getContent().set(
                                            inner.getContent().indexOf(rawRunChild), text);
                                }
                            }
                            unwrapped.add(inner);
                        }
                    }
                } else {
                    unwrapped.add(raw);
                }
            }
            paragraph.getContent().clear();
            paragraph.getContent().addAll(unwrapped);
        }
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan,
                rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void hiddenRevisionMarkInTheGeneratedDocumentIsRejected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));

        var paragraph = paragraphs(wordPackage).get(0);
        var pPr = paragraph.getPPr() != null
                ? paragraph.getPPr() : new org.docx4j.wml.PPr();
        paragraph.setPPr(pPr);
        var numPr = new org.docx4j.wml.PPrBase.NumPr();
        var numberingChange = new org.docx4j.wml.CTTrackChangeNumbering();
        numberingChange.setId(java.math.BigInteger.valueOf(999_999L));
        numberingChange.setAuthor("Rivell");
        numberingChange.setDate(javax.xml.datatype.DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(RedlineFixturePipeline.EXECUTED_AT.toString()));
        numPr.setNumberingChange(numberingChange);
        pPr.setNumPr(numPr);
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan,
                rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void revisionAuthorTamperingIsRejected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));

        var markup = new ArrayList<org.docx4j.wml.CTMarkup>();
        collectMarkup(wordPackage.getMainDocumentPart().getJaxbElement(), markup);
        ((org.docx4j.wml.CTTrackChange) markup.get(0)).setAuthor("Someone Else");
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan,
                rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    @Test
    void revisionIdOutsidePlannedRangesIsRejected() throws Exception {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var wordPackage = WordprocessingMLPackage.load(
                new ByteArrayInputStream(rendered.bytes()));

        var markup = new ArrayList<org.docx4j.wml.CTMarkup>();
        collectMarkup(wordPackage.getMainDocumentPart().getJaxbElement(), markup);
        markup.get(0).setId(java.math.BigInteger.valueOf(9_000_000L));
        var output = new ByteArrayOutputStream();
        wordPackage.save(output);

        var report = verifier.verify(source, output.toByteArray(), plan,
                rendered.changes());

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
    }

    private static List<P> paragraphs(WordprocessingMLPackage wordPackage) {
        var paragraphs = new ArrayList<P>();
        for (var raw : wordPackage.getMainDocumentPart().getJaxbElement().getBody()
                .getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof P paragraph) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private static void collectMarkup(Object node, List<org.docx4j.wml.CTMarkup> markup) {
        if (node == null) {
            return;
        }
        if (node instanceof org.docx4j.wml.CTMarkup element) {
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

    @Test
    void replacingARunInClauseDeletesItsTextAndKeepsTheHeading() throws Exception {
        // "5. Residual Knowledge. The Recipient…" is one paragraph: the heading and the clause
        // text share it, so a replacement that only adds paragraphs would leave the old clause
        var fixture = "blind-nda-run-in-headings.docx";
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
        var heading = structure.nodes().stream()
                .filter(node -> node.text() != null && node.text().startsWith("5. Residual Knowledge."))
                .findFirst().orElseThrow();
        var language = "Neither party may use Confidential Information retained in unaided memory.";
        var setId = UUID.randomUUID();
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(RedlineTestStructures.replace(setId, 0, heading.id(), language)));
        var plan = RedlineFixturePipeline.plan(structure, set, Map.of());
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
        var accepted = paragraphTexts(rendered.bytes(), true);
        assertThat(accepted).contains("5. Residual Knowledge.", language);
        assertThat(accepted).noneMatch(text -> text.contains("retained in the unaided memory"));
        assertThat(paragraphTexts(rendered.bytes(), false)).contains(heading.text())
                .doesNotContain(language);
    }

    /** Word's accept-all (or reject-all) paragraph texts, read from the raw XML. */
    private static List<String> paragraphTexts(byte[] docx, boolean accept) throws Exception {
        var xml = new String(entries(docx).get("word/document.xml"), java.nio.charset.StandardCharsets.UTF_8);
        var texts = new ArrayList<String>();
        var paragraph = java.util.regex.Pattern.compile("<w:p[ >].*?</w:p>", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        while (paragraph.find()) {
            var p = paragraph.group();
            var properties = p.contains("<w:pPr>") ? p.substring(0, p.indexOf("</w:pPr>") + 1) : "";
            if (properties.contains(accept ? "<w:del " : "<w:ins ")) {
                continue;
            }
            var visible = accept ? p.replaceAll("(?s)<w:del [^>]*>.*?</w:del>", "")
                    : p.replaceAll("(?s)<w:ins [^>]*>.*?</w:ins>", "");
            var text = new StringBuilder();
            var run = java.util.regex.Pattern.compile(accept ? "<w:t(?: [^>]*)?>([^<]*)</w:t>"
                    : "<w:(?:t|delText)(?: [^>]*)?>([^<]*)</w:(?:t|delText)>").matcher(visible);
            while (run.find()) {
                text.append(run.group(1));
            }
            texts.add(text.toString());
        }
        return texts;
    }

    @Test
    void partsDocx4jWouldRewriteStayByteIdenticalAndTheRedlineVerifies() throws Exception {
        // Real Word files carry a settings part (read for the protection check) and often
        // markup docx4j's schema rejects; saving the whole package used to rewrite both.
        var fixture = "m9-with-parts.docx";
        var plan = replacePlan(fixture, "1. Limitation of Liability",
                "Liability is capped at the fees paid.");
        var source = withSettingsAndUnusualFooter(RedlineFixturePipeline.fixture(fixture));

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
        var generated = entries(rendered.bytes());
        var original = entries(source);
        assertThat(generated.keySet()).containsExactlyElementsOf(original.keySet());
        for (var name : original.keySet()) {
            if (!name.equals("word/document.xml")) {
                assertThat(generated.get(name)).as(name).isEqualTo(original.get(name));
            }
        }
    }

    private static byte[] withSettingsAndUnusualFooter(byte[] bytes) throws Exception {
        var settings = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:zoom w:percent="100"/>
                  <w:defaultTabStop w:val="720"/>
                </w:settings>
                """;
        var footer = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:p><w:r><w:t>Page </w:t><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r>"
                + "</w:fldSimple></w:r></w:p></w:ftr>";
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes));
                var out = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var raw = zip.readAllBytes();
                var content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                var name = entry.getName();
                if (name.equals("[Content_Types].xml")) {
                    content = content.replace("</Types>", "<Override PartName=\"/word/settings.xml\" "
                            + "ContentType=\"application/vnd.openxmlformats-officedocument."
                            + "wordprocessingml.settings+xml\"/></Types>");
                } else if (name.equals("word/_rels/document.xml.rels")) {
                    content = content.replace("</Relationships>", "<Relationship Id=\"rIdSettings\" "
                            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                            + "relationships/settings\" Target=\"settings.xml\"/></Relationships>");
                } else if (name.equals("word/footer1.xml")) {
                    content = footer;
                }
                out.putNextEntry(new ZipEntry(name));
                out.write(name.endsWith(".xml") || name.endsWith(".rels")
                        ? content.getBytes(java.nio.charset.StandardCharsets.UTF_8) : raw);
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("word/settings.xml"));
            out.write(settings.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> entries(byte[] bytes) throws Exception {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] stripPart(byte[] bytes, String partName) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes));
                var out = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (partName.equals(entry.getName())) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(zip.readAllBytes());
                out.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
