package com.rivell.workbench.platform.redline;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.CTMarkup;
import org.docx4j.wml.DelText;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.springframework.stereotype.Component;

import com.rivell.workbench.redline.ChangeVerification;
import com.rivell.workbench.redline.DocumentExecutionPlan;
import com.rivell.workbench.redline.PlannedDocumentOperation;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineVerificationStatus;
import com.rivell.workbench.redline.RedlineVerifier;
import com.rivell.workbench.redline.VerificationReport;

/**
 * Independent round-trip verification: the generated DOCX is reopened from bytes and
 * checked layer by layer. Text-view equality proves that rejecting all revisions
 * reproduces the frozen source and accepting all revisions reproduces exactly the
 * approved change set; per-change checks map each ApprovedChange to its tracked
 * representation. Never trusts the renderer's in-memory objects.
 */
@Component
public class DocxRedlineVerifier implements RedlineVerifier {

    @Override
    public VerificationReport verify(
            byte[] sourceBytes, byte[] generatedBytes, DocumentExecutionPlan plan) {
        WordprocessingMLPackage generated;
        WordprocessingMLPackage source;
        try {
            generated = WordprocessingMLPackage.load(new ByteArrayInputStream(generatedBytes));
        } catch (Exception e) {
            return VerificationReport.failed(RedlineFailureCode.ROUND_TRIP_FAILED,
                    "The generated document could not be reopened.", List.of());
        }
        try {
            source = WordprocessingMLPackage.load(new ByteArrayInputStream(sourceBytes));
        } catch (Exception e) {
            return VerificationReport.failed(RedlineFailureCode.ROUND_TRIP_FAILED,
                    "The frozen source document could not be reopened.", List.of());
        }
        if (!DocxRedlineSupport.isDocxMainPart(generated)) {
            return VerificationReport.failed(RedlineFailureCode.ROUND_TRIP_FAILED,
                    "The generated package is no longer a standard DOCX document.", List.of());
        }

        var partProblem = partProblem(source, generated, sourceBytes, generatedBytes);
        if (partProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    partProblem, List.of());
        }

        var sourceView = sourceView(source);
        var rejectView = rejectView(generated);
        var acceptView = acceptView(generated);
        var expectedAcceptView = expectedAcceptView(source, plan);
        if (!rejectView.equals(sourceView)) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    "Rejecting all tracked changes does not reproduce the frozen source.",
                    List.of());
        }
        if (!acceptView.equals(expectedAcceptView)) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    "Accepting all tracked changes does not reproduce the approved changes.",
                    List.of());
        }

        var markup = DocxRedlineSupport.markupElements(
                generated.getMainDocumentPart().getJaxbElement());
        var ids = new HashSet<Long>();
        for (var element : markup) {
            if (element.getId() != null && !ids.add(element.getId().longValue())) {
                return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                        "The generated document contains duplicate revision identifiers.",
                        List.of());
            }
        }

        var deletedParagraphs = deletedParagraphTexts(generated);
        var insertedParagraphs = insertedParagraphTexts(generated);
        var expectedDeleted = new ArrayList<String>();
        var expectedInserted = new ArrayList<String>();
        for (var operation : plan.operations()) {
            for (var target : operation.deletionTargets()) {
                expectedDeleted.add(target.expectedText());
            }
            if (operation.insertion() != null) {
                expectedInserted.addAll(operation.insertion().paragraphs());
            }
        }
        if (!sameMultiset(deletedParagraphs, expectedDeleted)) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    "Tracked deletions do not match the approved changes exactly.", List.of());
        }
        if (!sameMultiset(insertedParagraphs, expectedInserted)) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    "Tracked insertions do not match the approved changes exactly.", List.of());
        }

        var changeResults = new ArrayList<ChangeVerification>();
        for (var operation : plan.operations()) {
            changeResults.add(verifyChange(operation, deletedParagraphs, insertedParagraphs));
        }
        if (changeResults.stream()
                .anyMatch(result -> result.status() == RedlineVerificationStatus.FAILED)) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    "At least one approved change is not represented by its tracked change.",
                    changeResults);
        }
        return VerificationReport.verified(changeResults);
    }

    private ChangeVerification verifyChange(
            PlannedDocumentOperation operation,
            List<String> deletedParagraphs,
            List<String> insertedParagraphs) {
        var expectedDeleted = operation.deletionTargets().stream()
                .map(com.rivell.workbench.redline.PlannedParagraph::expectedText)
                .toList();
        if (!containsOrderedSubsequence(deletedParagraphs, expectedDeleted)) {
            return new ChangeVerification(operation.approvedChangeId(),
                    RedlineVerificationStatus.FAILED,
                    "The tracked deletion for change " + operation.ordinal()
                            + " is missing or out of order.");
        }
        var expectedInserted = operation.insertion() == null
                ? List.<String>of() : operation.insertion().paragraphs();
        if (!containsContiguousRun(insertedParagraphs, expectedInserted)) {
            return new ChangeVerification(operation.approvedChangeId(),
                    RedlineVerificationStatus.FAILED,
                    "The tracked insertion for change " + operation.ordinal()
                            + " is missing or not contiguous.");
        }
        return new ChangeVerification(operation.approvedChangeId(),
                RedlineVerificationStatus.VERIFIED, null);
    }

    private List<String> sourceView(WordprocessingMLPackage wordPackage) {
        var entries = new ArrayList<String>();
        walk(wordPackage, (paragraph, table) -> {
            if (paragraph != null) {
                entries.add(collectText(paragraph, TextMode.ALL));
            } else {
                entries.add(tableText(table));
            }
        });
        return entries;
    }

    private List<String> rejectView(WordprocessingMLPackage wordPackage) {
        var entries = new ArrayList<String>();
        walk(wordPackage, (paragraph, table) -> {
            if (table != null) {
                entries.add(tableText(table));
            } else if (!isInserted(paragraph)) {
                entries.add(collectText(paragraph, TextMode.REJECTED));
            }
        });
        return entries;
    }

    private List<String> acceptView(WordprocessingMLPackage wordPackage) {
        var entries = new ArrayList<String>();
        walk(wordPackage, (paragraph, table) -> {
            if (table != null) {
                entries.add(tableText(table));
            } else if (!isDeleted(paragraph)) {
                entries.add(collectText(paragraph, TextMode.ACCEPTED));
            }
        });
        return entries;
    }

    private List<String> expectedAcceptView(
            WordprocessingMLPackage source, DocumentExecutionPlan plan) {
        var entries = new ArrayList<String>();
        walk(source, (paragraph, table) -> entries.add(
                paragraph != null ? collectText(paragraph, TextMode.ALL) : tableText(table)));
        var bodyIndexToViewIndex = new HashMap<Integer, Integer>();
        for (var anchor : viewAnchors(source)) {
            bodyIndexToViewIndex.put(anchor.bodyIndex(), anchor.viewIndex());
        }

        var deleted = new HashSet<Integer>();
        var insertedAfter = new LinkedHashMap<Integer, List<String>>();
        var prepended = new ArrayList<String>();
        for (var operation : plan.operations()) {
            for (var target : operation.deletionTargets()) {
                var index = bodyIndexToViewIndex.get(target.bodyIndex());
                if (index != null) {
                    deleted.add(index);
                }
            }
            if (operation.insertion() != null) {
                var after = operation.insertion().afterBodyIndex();
                if (after < 0) {
                    prepended.addAll(operation.insertion().paragraphs());
                } else {
                    var index = bodyIndexToViewIndex.get(after);
                    if (index != null) {
                        insertedAfter.computeIfAbsent(index, ignored -> new ArrayList<>())
                                .addAll(operation.insertion().paragraphs());
                    }
                }
            }
        }

        var expected = new ArrayList<String>();
        expected.addAll(prepended);
        for (var i = 0; i < entries.size(); i++) {
            if (!deleted.contains(i)) {
                expected.add(entries.get(i));
            }
            var insertions = insertedAfter.get(i);
            if (insertions != null) {
                expected.addAll(insertions);
            }
        }
        return expected;
    }

    private List<ViewAnchor> viewAnchors(WordprocessingMLPackage wordPackage) {
        var anchors = new ArrayList<ViewAnchor>();
        var body = DocxRedlineSupport.body(wordPackage);
        var viewIndex = new int[] {0};
        collectAnchors(body.getContent(), null, anchors, viewIndex);
        return anchors;
    }

    private void collectAnchors(
            List<Object> content, Integer anchorBodyIndex, List<ViewAnchor> anchors,
            int[] viewIndex) {
        for (var i = 0; i < content.size(); i++) {
            var element = DocxRedlineSupport.unwrap(content.get(i));
            var bodyIndex = anchorBodyIndex != null ? anchorBodyIndex : i;
            if (element instanceof P) {
                anchors.add(new ViewAnchor(bodyIndex, viewIndex[0]++));
            } else if (element instanceof Tbl) {
                anchors.add(new ViewAnchor(bodyIndex, viewIndex[0]++));
            } else if (element instanceof SdtBlock sdtBlock) {
                collectAnchors(sdtBlock.getSdtContent().getContent(), bodyIndex, anchors,
                        viewIndex);
            }
        }
    }

    private record ViewAnchor(int bodyIndex, int viewIndex) {
    }

    private interface EntryVisitor {
        void accept(P paragraph, Tbl table);
    }

    private void walk(WordprocessingMLPackage wordPackage, EntryVisitor visitor) {
        walkContent(DocxRedlineSupport.body(wordPackage).getContent(), visitor);
    }

    private void walkContent(List<Object> content, EntryVisitor visitor) {
        for (var raw : content) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof P paragraph) {
                visitor.accept(paragraph, null);
            } else if (element instanceof Tbl table) {
                visitor.accept(null, table);
            } else if (element instanceof SdtBlock sdtBlock) {
                walkContent(sdtBlock.getSdtContent().getContent(), visitor);
            }
        }
    }

    private String tableText(Tbl table) {
        var text = new StringBuilder();
        for (var rawRow : table.getContent()) {
            var row = DocxRedlineSupport.unwrap(rawRow);
            if (!(row instanceof Tr)) {
                continue;
            }
            for (var rawCell : ((Tr) row).getContent()) {
                var cell = DocxRedlineSupport.unwrap(rawCell);
                if (!(cell instanceof Tc)) {
                    continue;
                }
                for (var rawCellChild : ((Tc) cell).getContent()) {
                    var cellChild = DocxRedlineSupport.unwrap(rawCellChild);
                    if (cellChild instanceof P paragraph) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(collectText(paragraph, TextMode.ALL));
                    }
                }
            }
        }
        return text.toString();
    }

    private enum TextMode {
        ALL, ACCEPTED, REJECTED
    }

    private String collectText(P paragraph, TextMode mode) {
        var text = new StringBuilder();
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                collectRunText(run, text);
            } else if (element instanceof RunDel del) {
                if (mode != TextMode.ACCEPTED) {
                    collectWrappedText(del.getCustomXmlOrSmartTagOrSdt(), text);
                }
            } else if (element instanceof RunIns ins) {
                if (mode != TextMode.REJECTED) {
                    collectWrappedText(ins.getCustomXmlOrSmartTagOrSdt(), text);
                }
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run) {
                        collectRunText(run, text);
                    } else if (linkChild instanceof RunDel del
                            && mode != TextMode.ACCEPTED) {
                        collectWrappedText(del.getCustomXmlOrSmartTagOrSdt(), text);
                    } else if (linkChild instanceof RunIns ins
                            && mode != TextMode.REJECTED) {
                        collectWrappedText(ins.getCustomXmlOrSmartTagOrSdt(), text);
                    }
                }
            }
        }
        return text.toString();
    }

    private void collectWrappedText(List<Object> wrapped, StringBuilder text) {
        for (var raw : wrapped) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                collectRunText(run, text);
            }
        }
    }

    private void collectRunText(R run, StringBuilder text) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof Text textElement) {
                text.append(textElement.getValue());
            } else if (element instanceof DelText delText) {
                text.append(delText.getValue());
            } else if (element instanceof Br || element instanceof R.Cr) {
                text.append('\n');
            } else if (element instanceof R.Tab) {
                text.append('\t');
            }
        }
    }

    private List<String> deletedParagraphTexts(WordprocessingMLPackage wordPackage) {
        var texts = new ArrayList<String>();
        walk(wordPackage, (paragraph, table) -> {
            if (paragraph != null && isDeleted(paragraph)) {
                texts.add(collectText(paragraph, TextMode.REJECTED));
            }
        });
        return texts;
    }

    private List<String> insertedParagraphTexts(WordprocessingMLPackage wordPackage) {
        var texts = new ArrayList<String>();
        walk(wordPackage, (paragraph, table) -> {
            if (paragraph != null && isInserted(paragraph)) {
                texts.add(collectText(paragraph, TextMode.ACCEPTED));
            }
        });
        return texts;
    }

    private static boolean isDeleted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getDel() != null;
    }

    private static boolean isInserted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getIns() != null;
    }

    private static boolean containsOrderedSubsequence(List<String> haystack,
            List<String> needle) {
        var cursor = 0;
        for (var value : haystack) {
            if (cursor < needle.size() && needle.get(cursor).equals(value)) {
                cursor++;
            }
        }
        return cursor == needle.size();
    }

    private static boolean containsContiguousRun(List<String> haystack, List<String> needle) {
        if (needle.isEmpty()) {
            return true;
        }
        for (var start = 0; start + needle.size() <= haystack.size(); start++) {
            if (haystack.subList(start, start + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameMultiset(List<String> first, List<String> second) {
        if (first.size() != second.size()) {
            return false;
        }
        var counts = new HashMap<String, Integer>();
        for (var value : first) {
            counts.merge(value, 1, Integer::sum);
        }
        for (var value : second) {
            var remaining = counts.get(value);
            if (remaining == null || remaining == 0) {
                return false;
            }
            counts.put(value, remaining - 1);
        }
        return true;
    }

    private String partProblem(
            WordprocessingMLPackage source,
            WordprocessingMLPackage generated,
            byte[] sourceBytes,
            byte[] generatedBytes) {
        var sourceZip = zipEntries(sourceBytes);
        var generatedZip = zipEntries(generatedBytes);
        for (var part : source.getParts().getParts().keySet()) {
            var name = part.getName().startsWith("/")
                    ? part.getName().substring(1) : part.getName();
            if (isPackageMetadata(name) || "word/document.xml".equals(name)) {
                continue;
            }
            var generatedPart = generatedZip.get(name);
            if (generatedPart == null) {
                return "A frozen source document part is missing from the generated redline ("
                        + name + ").";
            }
            var sourcePart = sourceZip.get(name);
            if (sourcePart != null && !Arrays.equals(sourcePart, generatedPart)) {
                return "A frozen source document part changed during redline execution ("
                        + name + ").";
            }
        }
        for (var name : generatedZip.keySet()) {
            if (!sourceZip.containsKey(name)) {
                return "The generated redline contains unexpected package content.";
            }
        }
        return null;
    }

    private static boolean isPackageMetadata(String name) {
        return "[Content_Types].xml".equals(name) || name.endsWith(".rels");
    }

    private static Map<String, byte[]> zipEntries(byte[] bytes) {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (Exception e) {
            return Map.of();
        }
        return entries;
    }
}
