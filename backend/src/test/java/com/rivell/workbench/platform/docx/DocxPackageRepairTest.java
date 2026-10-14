package com.rivell.workbench.platform.docx;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DocxPackageRepairTest {

    private static final String RELS = DocxTestPackages.RELS_ENTRY;
    private static final String IMAGE = "word/media/image1.png";
    private static final String SPACED_IMAGE = "word/media/image 1.png";

    private static final String HYPERLINK_RELATIONSHIP = "<Relationship Id=\"rIdLink\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
            + "Target=\"https://example.test/x\" TargetMode=\"External\"/>";

    @Test
    void removesARelationshipToAMissingFootnotesPartAndKeepsEveryOtherEntryByteIdentical()
            throws Exception {
        var damaged = damagedPackage(DocxTestPackages.FOOTNOTES_RELATIONSHIP);
        var before = DocxTestPackages.entries(damaged);

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isTrue();
        var after = DocxTestPackages.entries(result.bytes());
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        for (var entry : before.entrySet()) {
            if (!RELS.equals(entry.getKey())) {
                assertThat(after.get(entry.getKey())).isEqualTo(entry.getValue());
            }
        }
        assertThat(rels(result.bytes())).doesNotContain("rIdFootnotes").contains("rIdStyles");
    }

    @Test
    void removesARelationshipToAMissingCommentsPart() throws Exception {
        var damaged = damagedPackage(DocxTestPackages.COMMENTS_RELATIONSHIP);

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isTrue();
        assertThat(rels(result.bytes())).doesNotContain("rIdComments").contains("rIdStyles");
    }

    @Test
    void keepsAnExternalRelationshipEvenWhenAnInternalOneIsRemoved() throws Exception {
        var damaged = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"), RELS,
                DocxTestPackages.relsDocument(DocxTestPackages.STYLES_RELATIONSHIP,
                        HYPERLINK_RELATIONSHIP, DocxTestPackages.FOOTNOTES_RELATIONSHIP));

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isTrue();
        assertThat(rels(result.bytes()))
                .contains("rIdLink", "rIdStyles")
                .doesNotContain("rIdFootnotes");
    }

    @Test
    void returnsAnIntactPackageUnchangedAndNotRepaired() throws Exception {
        var intact = DocxTestPackages.fixture("m9-simple-replace.docx");

        var result = DocxPackageRepair.repair(intact);

        assertThat(result.repaired()).isFalse();
        assertThat(result.bytes()).isEqualTo(intact);
    }

    @Test
    void keepsAPercentEncodedTargetWhoseDecodedPartExists() throws Exception {
        var source = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"), SPACED_IMAGE, imageBytes());
        var damaged = DocxTestPackages.withEntry(source, RELS, DocxTestPackages.relsDocument(
                DocxTestPackages.STYLES_RELATIONSHIP,
                "<Relationship Id=\"rIdImage\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                        + " Target=\"media/image%201.png\"/>"));

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isFalse();
        assertThat(result.bytes()).isEqualTo(damaged);
    }

    @Test
    void keepsATargetWhosePartDiffersOnlyInLetterCase() throws Exception {
        var source = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"), IMAGE, imageBytes());
        var damaged = DocxTestPackages.withEntry(source, RELS, DocxTestPackages.relsDocument(
                DocxTestPackages.STYLES_RELATIONSHIP,
                "<Relationship Id=\"rIdImage\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                        + " Target=\"media/Image1.PNG\"/>"));

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isFalse();
        assertThat(result.bytes()).isEqualTo(damaged);
    }

    @Test
    void resolvesParentSegmentsAndAbsoluteTargetsAgainstThePackageRoot() throws Exception {
        var source = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"),
                "customXml/item1.xml", "<item/>");
        var damaged = DocxTestPackages.withEntry(source, RELS, DocxTestPackages.relsDocument(
                DocxTestPackages.STYLES_RELATIONSHIP,
                "<Relationship Id=\"rIdItem\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml\""
                        + " Target=\"../customXml/item1.xml\"/>",
                "<Relationship Id=\"rIdMissingItem\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml\""
                        + " Target=\"../customXml/missing.xml\"/>",
                "<Relationship Id=\"rIdAbsolute\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                        + " Target=\"/word/document.xml\"/>"));

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isTrue();
        assertThat(rels(result.bytes()))
                .contains("rIdItem", "rIdAbsolute", "rIdStyles")
                .doesNotContain("rIdMissingItem");
    }

    @Test
    void doesNotExpandDoctypeEntitiesAndLeavesSuchARelsUnchanged() throws Exception {
        var doctypeRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE Relationships [ <!ENTITY xxe \"expanded-target\"> ]>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "<Relationship Id=\"rIdEntity\" Target=\"&xxe;\"/>\n"
                + DocxTestPackages.FOOTNOTES_RELATIONSHIP
                + "\n</Relationships>\n";
        var damaged = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"), RELS, doctypeRels);

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isFalse();
        assertThat(result.bytes()).isEqualTo(damaged);
    }

    private static byte[] damagedPackage(String missingRelationship) {
        return DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"), RELS,
                DocxTestPackages.relsDocument(DocxTestPackages.STYLES_RELATIONSHIP,
                        missingRelationship));
    }

    private static String rels(byte[] packageBytes) {
        return new String(DocxTestPackages.entries(packageBytes).get(RELS),
                StandardCharsets.UTF_8);
    }

    private static byte[] imageBytes() {
        return new byte[] {1, 2, 3};
    }
}
