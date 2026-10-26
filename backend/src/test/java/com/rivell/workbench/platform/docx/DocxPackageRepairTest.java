package com.rivell.workbench.platform.docx;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

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

    /**
     * An intact package passes through without a copy of its parts. A 24 MB part
     * compresses to a few kilobytes, so reading it into memory is what the allocation counter
     * would show.
     */
    @Test
    void scansAnIntactPackageWithoutCopyingItsParts() throws Exception {
        var intact = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"),
                "word/media/large.bin", new byte[24 * 1024 * 1024]);

        var allocated = allocatedBytesDuring(() -> {
            var result = DocxPackageRepair.repair(intact);
            assertThat(result.repaired()).isFalse();
            assertThat(result.bytes()).isSameAs(intact);
        });

        assertThat(allocated).isLessThan(4L * 1024 * 1024);
    }

    @Test
    void repairsADamagedPackageThatHoldsALargePartAndKeepsThePartIdentical() throws Exception {
        var large = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(large, (byte) 7);
        var damaged = DocxTestPackages.withEntry(
                damagedPackage(DocxTestPackages.FOOTNOTES_RELATIONSHIP),
                "word/media/large.bin", large);

        var result = DocxPackageRepair.repair(damaged);

        assertThat(result.repaired()).isTrue();
        assertThat(rels(result.bytes())).doesNotContain("rIdFootnotes");
        assertThat(DocxTestPackages.entries(result.bytes()).get("word/media/large.bin"))
                .isEqualTo(large);
    }

    private static long allocatedBytesDuring(ThrowingRunnable action) throws Exception {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var thread = Thread.currentThread().threadId();
        action.run();
        var before = threads.getThreadAllocatedBytes(thread);
        action.run();
        return threads.getThreadAllocatedBytes(thread) - before;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
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
