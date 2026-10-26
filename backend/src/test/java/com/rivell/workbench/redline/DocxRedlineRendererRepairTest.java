package com.rivell.workbench.redline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.docx4j.TraversalUtil;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.DelText;
import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.platform.docx.DocxRedlineRenderer;
import com.rivell.workbench.platform.docx.DocxTestPackages;
import com.rivell.workbench.platform.parsing.DocxParserProperties;
import com.rivell.workbench.platform.parsing.DocxStructuralParser;
import com.rivell.workbench.platform.parsing.RedlineFixturePipeline;

/** A source package Word would repair must still execute as a redline. */
class DocxRedlineRendererRepairTest {

    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;

    private final DocxRedlineRenderer renderer =
            new DocxRedlineRenderer(new DocxParserProperties(MAX_UNCOMPRESSED_BYTES));

    @Test
    void aDamagedSourcePackageIsRepairedAndCanBeRedlined() throws Exception {
        var damaged = DocxTestPackages.withEntry(
                RedlineFixturePipeline.fixture("m9-simple-replace.docx"),
                DocxTestPackages.RELS_ENTRY,
                DocxTestPackages.relsDocument(DocxTestPackages.STYLES_RELATIONSHIP,
                        DocxTestPackages.FOOTNOTES_RELATIONSHIP));
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var parsed = new DocxStructuralParser(new DocxParserProperties(MAX_UNCOMPRESSED_BYTES))
                .parse(new ByteArrayInputStream(damaged), versionId);
        assertThat(parsed.status())
                .as("the damaged package must parse through the repair first")
                .isEqualTo(ParseStatus.OK);
        var structure = new ContractStructure(contractId, 1, parsed.parserVersion(),
                parsed.status(), parsed.warnings(), parsed.nodes());
        var setId = UUID.randomUUID();
        var change = RedlineTestStructures.replace(setId, 0,
                RedlineFixturePipeline.headingId(structure, "2. Limitation of Liability"),
                "Liability is capped at the fees paid.");
        var set = RedlineTestStructures.set(UUID.randomUUID(), contractId, versionId, 1,
                List.of(change));
        var plan = RedlineFixturePipeline.plan(structure, set, Map.of());

        var rendered = renderer.render(damaged, plan);

        assertThat(rendered.changes()).hasSize(1);
        // The artifact itself must be loadable without a repair: a repaired read must not
        // produce a redline that Word would offer to repair again.
        var reopened = WordprocessingMLPackage.load(new ByteArrayInputStream(rendered.bytes()));
        assertThat(deletedTexts(reopened))
                .contains("Supplier liability shall be unlimited.");
    }

    private static List<String> deletedTexts(WordprocessingMLPackage wordPackage) {
        var found = new ArrayList<String>();
        collect(wordPackage.getMainDocumentPart().getJaxbElement(), found);
        return found;
    }

    private static void collect(Object node, List<String> deletedText) {
        if (node instanceof DelText text) {
            deletedText.add(text.getValue());
        }
        var children = TraversalUtil.getChildrenImpl(node);
        if (children == null) {
            return;
        }
        for (var child : children) {
            collect(child, deletedText);
        }
    }
}
