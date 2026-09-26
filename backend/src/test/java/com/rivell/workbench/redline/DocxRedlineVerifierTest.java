package com.rivell.workbench.redline;

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
import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.platform.parsing.RedlineFixturePipeline;
import com.rivell.workbench.platform.docx.DocxRedlineRenderer;
import com.rivell.workbench.platform.docx.DocxRedlineVerifier;

class DocxRedlineVerifierTest {

    private final DocxRedlineRenderer renderer = new DocxRedlineRenderer();
    private final DocxRedlineVerifier verifier = new DocxRedlineVerifier();

    @Test
    void simpleReplaceRoundTripsAndVerifiesEveryChange() {
        var fixture = "m9-simple-replace.docx";
        var plan = replacePlan(fixture, "2. Limitation of Liability",
                "Aggregate liability shall not exceed the fees paid in the prior months.");
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan);

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
                "1. Limitation of Liability", LegalConcept.LIMITATION_OF_LIABILITY,
                "3. Indemnification", LegalConcept.INDEMNIFICATION,
                "4. Confidentiality", LegalConcept.CONFIDENTIALITY,
                "5. Governing Law", LegalConcept.GOVERNING_LAW));
        var setId = UUID.randomUUID();
        var replace = RedlineTestStructures.replace(setId, 0,
                RedlineFixturePipeline.headingId(structure, "1. Limitation of Liability"),
                "Liability is capped at the fees paid in the prior twelve months.");
        var delete = RedlineTestStructures.delete(setId, 1,
                RedlineFixturePipeline.headingId(structure, "2. Non-Solicitation"));
        var insert = RedlineTestStructures.insert(setId, 2, LegalConcept.TERM_TERMINATION,
                "Either party may terminate for material breach after thirty days notice.");
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(replace, delete, insert));
        var plan = RedlineFixturePipeline.plan(structure, set, concepts);
        var source = RedlineFixturePipeline.fixture(fixture);

        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan);

        assertThat(report.passed()).as(String.valueOf(report.failureDetail())).isTrue();
        assertThat(report.changes()).hasSize(3);
        assertThat(report.changes())
                .allSatisfy(change -> assertThat(change.status())
                        .isEqualTo(RedlineVerificationStatus.VERIFIED));
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
        var report = verifier.verify(source, rendered.bytes(), differentPlan);

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

        var report = verifier.verify(source, output.toByteArray(), plan);

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
        var report = verifier.verify(source, stripped, plan);

        assertThat(report.passed()).isFalse();
        assertThat(report.failureCode()).isEqualTo(RedlineFailureCode.VERIFICATION_FAILED);
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
