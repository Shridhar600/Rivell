package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.platform.docx.DocxRedlineRenderer;
import com.rivell.workbench.platform.docx.DocxRedlineVerifier;
import com.rivell.workbench.platform.parsing.RedlineFixturePipeline;

/**
 * Writes one tracked-change DOCX per labeled M9 case to {@code target/word-validation/}
 * together with a machine-readable manifest. The Microsoft Word release gate opens these
 * artifacts on a Windows machine; the test itself only uses the real renderer and
 * verifier (no provider, no database, no network).
 */
class WordValidationArtifactTest {

    private static final Path OUTPUT = Path.of("target", "word-validation");

    private final DocxRedlineRenderer renderer = new DocxRedlineRenderer(new com.rivell.workbench.platform.parsing.DocxParserProperties(
                200L * 1024 * 1024));
    private final DocxRedlineVerifier verifier = new DocxRedlineVerifier(new com.rivell.workbench.platform.parsing.DocxParserProperties(
                200L * 1024 * 1024));

    @Test
    void writesWordValidationArtifactsAndManifest() throws IOException {
        Files.createDirectories(OUTPUT);
        var manifest = new ArrayList<Map<String, Object>>();

        manifest.add(caseWithPlan("simple-replace", "m9-simple-replace.docx",
                "REPLACE the Limitation of Liability clause body with approved fallback language",
                replacePlan("m9-simple-replace.docx", "2. Limitation of Liability",
                        "The total liability of Supplier shall not exceed two times the fees "
                                + "paid in the preceding 12 months."),
                "Supplier liability shall be unlimited."));
        manifest.add(caseWithPlan("fragmented-replace", "m9-fragmented-run.docx",
                "REPLACE a clause whose text is split across five runs",
                replacePlan("m9-fragmented-run.docx", "2. Limitation of Liability",
                        "Liability is capped at the fees paid in the prior twelve months."),
                "Supplier liability shall be unlimited."));
        manifest.add(caseWithPlan("delete-clause", "m9-delete.docx",
                "DELETE the Non-Solicitation clause (heading and body)",
                deletePlan("m9-delete.docx", "2. Non-Solicitation"),
                "2. Non-Solicitation"));
        manifest.add(caseWithPlan("insert-missing-provision", "m9-insert.docx",
                "INSERT a missing Limitation of Liability provision after Indemnification",
                insertPlan("m9-insert.docx", CoreConcepts.LIMITATION_OF_LIABILITY,
                        "Liability is capped at fees paid in the prior 12 months."),
                null));
        manifest.add(caseWithPlan("multi-change", "m9-multi-change.docx",
                "REPLACE + DELETE + INSERT in one execution",
                multiChangePlan(), "2. Non-Solicitation"));
        manifest.add(caseWithPlan("multi-paragraph-replace", "m9-multi-paragraph.docx",
                "REPLACE with multi-paragraph approved language including an empty line",
                replacePlan("m9-multi-paragraph.docx", "1. Limitation of Liability",
                        "First replacement line.\nSecond replacement line.\n\nFourth "
                                + "replacement line."),
                "First paragraph of the original cap language."));
        manifest.add(caseWithPlan("hyperlink-replace", "m9-hyperlink-target.docx",
                "REPLACE a clause containing a hyperlink (runs wrapped inside the hyperlink)",
                replacePlan("m9-hyperlink-target.docx", "1. Limitation of Liability",
                        "Liability is capped at the fees paid."),
                "the liability page"));
        manifest.add(caseWithPlan("sdt-delete", "m9-sdt-clause.docx",
                "DELETE a clause whose body paragraphs are inside a content control",
                deletePlan("m9-sdt-clause.docx", "1. Services"),
                "Supplier shall provide the services described in Schedule A."));
        manifest.add(caseWithPlan("numbered-replace", "m9-numbered.docx",
                "REPLACE a clause in a document with automatic list numbering",
                replacePlan("m9-numbered.docx", "2. Limitation of Liability",
                        "Liability is capped at the fees paid."),
                "Supplier liability shall be unlimited."));
        manifest.add(caseWithPlan("parts-replace", "m9-with-parts.docx",
                "REPLACE a clause in a package with header/footer/image parts",
                replacePlan("m9-with-parts.docx", "1. Limitation of Liability",
                        "Liability is capped at the fees paid."),
                "Supplier liability shall be unlimited."));

        writeManifest(manifest);
        System.out.println("WORD VALIDATION ARTIFACTS: " + OUTPUT.toAbsolutePath());
    }

    private Map<String, Object> caseWithPlan(
            String name,
            String fixture,
            String changeDescription,
            DocumentExecutionPlan plan,
            String deletedSample) throws IOException {
        var source = RedlineFixturePipeline.fixture(fixture);
        var rendered = renderer.render(source, plan);
        var report = verifier.verify(source, rendered.bytes(), plan, rendered.changes());
        if (!report.passed()) {
            throw new IllegalStateException("case " + name + " failed verification: "
                    + report.failureDetail());
        }
        var path = OUTPUT.resolve(name + ".docx");
        Files.write(path, rendered.bytes());

        var entry = new LinkedHashMap<String, Object>();
        entry.put("case", name);
        entry.put("artifact", path.getFileName().toString());
        entry.put("sourceFixture", fixture);
        entry.put("change", changeDescription);
        entry.put("approvedLanguages", plan.operations().stream()
                .filter(operation -> operation.insertion() != null)
                .flatMap(operation -> operation.insertion().paragraphs().stream())
                .toList());
        entry.put("expectedRejectedTextSample", deletedSample);
        entry.put("artifactSha256", rendered.sha256());
        entry.put("verification", "VERIFIED");
        return entry;
    }

    private void writeManifest(List<Map<String, Object>> manifest) throws IOException {
        var json = new StringBuilder("{\n  \"cases\": [\n");
        for (var i = 0; i < manifest.size(); i++) {
            json.append("    ").append(toJson(manifest.get(i)));
            json.append(i == manifest.size() - 1 ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(OUTPUT.resolve("manifest.json"), json.toString());
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n") + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            for (var entry : map.entrySet()) {
                parts.add("\"" + entry.getKey() + "\": " + toJson(entry.getValue()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        if (value instanceof List<?> list) {
            var parts = new ArrayList<String>();
            for (var item : list) {
                parts.add(toJson(item));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(value);
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

    private DocumentExecutionPlan deletePlan(String fixture, String headingText) {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.delete(setId, 0,
                RedlineFixturePipeline.headingId(structure, headingText));
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));
        return RedlineFixturePipeline.plan(structure, set, Map.of());
    }

    private DocumentExecutionPlan insertPlan(String fixture, ConceptKey concept,
            String language) {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = RedlineFixturePipeline.parse(fixture, contractId, versionId);
        var concepts = RedlineFixturePipeline.concepts(structure, Map.of(
                "2. Indemnification", CoreConcepts.INDEMNIFICATION,
                "3. Confidentiality", CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                "4. Term and Survival", CoreConcepts.TERM,
                "5. Governing Law", CoreConcepts.GOVERNING_LAW));
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.insert(setId, 0, concept, language);
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));
        return RedlineFixturePipeline.plan(structure, set, concepts);
    }

    private DocumentExecutionPlan multiChangePlan() {
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
        return RedlineFixturePipeline.plan(structure, set, concepts);
    }
}
