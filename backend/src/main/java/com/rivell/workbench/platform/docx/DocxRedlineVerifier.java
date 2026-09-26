package com.rivell.workbench.platform.docx;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.CTMarkup;
import org.docx4j.wml.CTTrackChange;
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

import com.rivell.workbench.redline.AppliedChangeReport;
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
            byte[] sourceBytes, byte[] generatedBytes, DocumentExecutionPlan plan,
            List<AppliedChangeReport> appliedReports) {
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

        var duplicateProblem = duplicateRevisionIdProblem(generated);
        if (duplicateProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    duplicateProblem, List.of());
        }
        var completenessProblem = revisionCompletenessProblem(generated, generatedBytes);
        if (completenessProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    completenessProblem, List.of());
        }
        var provenanceProblem = revisionProvenanceProblem(generated, plan);
        if (provenanceProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    provenanceProblem, List.of());
        }

        var structuralProblem = structuralProblem(generated);
        if (structuralProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    structuralProblem, List.of());
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

        var rangeProblem = rangeProblem(generated, plan, appliedReports);
        if (rangeProblem != null) {
            return VerificationReport.failed(RedlineFailureCode.VERIFICATION_FAILED,
                    rangeProblem, List.of());
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
        var anchorToViewIndex = new HashMap<String, Integer>();
        for (var anchor : viewAnchors(source)) {
            anchorToViewIndex.put(anchor.bodyIndex() + ":" + anchor.paragraphIndex(),
                    anchor.viewIndex());
        }

        var deleted = new HashSet<Integer>();
        var insertedAfter = new LinkedHashMap<Integer, List<String>>();
        var prepended = new ArrayList<String>();
        for (var operation : plan.operations()) {
            for (var target : operation.deletionTargets()) {
                var index = anchorToViewIndex.get(
                        target.bodyIndex() + ":" + target.paragraphIndex());
                if (index != null) {
                    deleted.add(index);
                }
            }
            if (operation.insertion() != null) {
                var after = operation.insertion().afterBodyIndex();
                if (after < 0) {
                    prepended.addAll(operation.insertion().paragraphs());
                } else {
                    var anchorIndex = operation.insertion().anchorParagraphIndex();
                    var index = anchorIndex == null
                            ? anchorToViewIndex.get(after + ":null")
                            : anchorToViewIndex.get(after + ":" + anchorIndex);
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
        collectAnchors(body.getContent(), null, anchors, viewIndex, new int[] {0});
        return anchors;
    }

    private void collectAnchors(
            List<Object> content, Integer anchorBodyIndex, List<ViewAnchor> anchors,
            int[] viewIndex, int[] paragraphOrdinal) {
        for (var i = 0; i < content.size(); i++) {
            var element = DocxRedlineSupport.unwrap(content.get(i));
            var bodyIndex = anchorBodyIndex != null ? anchorBodyIndex : i;
            if (element instanceof P) {
                anchors.add(new ViewAnchor(bodyIndex, paragraphOrdinal[0]++, viewIndex[0]++));
            } else if (element instanceof Tbl) {
                anchors.add(new ViewAnchor(bodyIndex, null, viewIndex[0]++));
            } else if (element instanceof SdtBlock sdtBlock) {
                collectAnchors(sdtBlock.getSdtContent().getContent(), bodyIndex, anchors,
                        viewIndex, paragraphOrdinal);
            }
        }
    }

    private record ViewAnchor(int bodyIndex, Integer paragraphIndex, int viewIndex) {
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

    /**
     * Layer C: deleted paragraphs must carry their text inside RunDel-wrapped runs as
     * delText; inserted paragraphs must carry theirs inside RunIns-wrapped runs as
     * normal text. Text outside those wrappers would not be tracked in Word.
     */
    private String structuralProblem(WordprocessingMLPackage wordPackage) {
        var problem = new String[] {null};
        walk(wordPackage, (paragraph, table) -> {
            if (problem[0] != null) {
                return;
            }
            if (table != null) {
                problem[0] = tableRevisionProblem(table);
                return;
            }
            if (isDeleted(paragraph)) {
                problem[0] = deletedParagraphProblem(paragraph);
            } else if (isInserted(paragraph)) {
                problem[0] = insertedParagraphProblem(paragraph);
            }
        });
        return problem[0];
    }

    /** M9 never mutates tables: any revision inside a table is unplanned. */
    private String tableRevisionProblem(Tbl table) {
        var problem = new String[] {null};
        for (var rawRow : table.getContent()) {
            var row = DocxRedlineSupport.unwrap(rawRow);
            if (!(row instanceof Tr tr)) {
                continue;
            }
            if (tr.getTrPr() != null && (tr.getTrPr().getIns() != null
                    || tr.getTrPr().getDel() != null)) {
                return "The generated document contains an unplanned revision inside a table.";
            }
            for (var rawCell : tr.getContent()) {
                var cell = DocxRedlineSupport.unwrap(rawCell);
                if (!(cell instanceof Tc tc)) {
                    continue;
                }
                if (tc.getTcPr() != null && (tc.getTcPr().getCellIns() != null
                        || tc.getTcPr().getCellDel() != null)) {
                    return "The generated document contains an unplanned revision inside "
                            + "a table.";
                }
                for (var rawCellChild : tc.getContent()) {
                    var cellChild = DocxRedlineSupport.unwrap(rawCellChild);
                    if (cellChild instanceof P cellParagraph
                            && (isDeleted(cellParagraph) || isInserted(cellParagraph))) {
                        return "The generated document contains an unplanned revision inside "
                                + "a table.";
                    }
                }
            }
        }
        return problem[0];
    }

    private String deletedParagraphProblem(P paragraph) {
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                if (runHasText(run)) {
                    return "A tracked deletion contains text outside a w:del wrapper.";
                }
            } else if (element instanceof RunIns) {
                return "A tracked deletion contains inserted run content.";
            } else if (element instanceof RunDel del) {
                for (var wrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                    var run = DocxRedlineSupport.unwrap(wrapped);
                    if (run instanceof R inner && runHasNormalText(inner)) {
                        return "A tracked deletion carries normal text instead of delText.";
                    }
                    if (run instanceof RunIns) {
                        return "A tracked deletion contains inserted run content.";
                    }
                }
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run && runHasText(run)) {
                        return "A tracked deletion contains text outside a w:del wrapper.";
                    }
                    if (linkChild instanceof RunDel del) {
                        for (var wrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                            var run = DocxRedlineSupport.unwrap(wrapped);
                            if (run instanceof R inner && runHasNormalText(inner)) {
                                return "A tracked deletion carries normal text instead "
                                        + "of delText.";
                            }
                        }
                    }
                    if (linkChild instanceof RunIns) {
                        return "A tracked deletion contains inserted run content.";
                    }
                }
            }
        }
        return null;
    }

    private String insertedParagraphProblem(P paragraph) {
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run && runHasText(run)) {
                return "A tracked insertion contains text outside a w:ins wrapper.";
            }
            if (element instanceof RunDel) {
                return "A tracked insertion contains deleted run content.";
            }
            if (element instanceof RunIns ins) {
                var problem = insertedWrapperProblem(ins.getCustomXmlOrSmartTagOrSdt());
                if (problem != null) {
                    return problem;
                }
            }
            if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run && runHasText(run)) {
                        return "A tracked insertion contains text outside a w:ins wrapper.";
                    }
                    if (linkChild instanceof RunDel) {
                        return "A tracked insertion contains deleted run content.";
                    }
                    if (linkChild instanceof RunIns ins) {
                        var problem = insertedWrapperProblem(
                                ins.getCustomXmlOrSmartTagOrSdt());
                        if (problem != null) {
                            return problem;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String insertedWrapperProblem(List<Object> wrapped) {
        for (var raw : wrapped) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run && runHasDeletedText(run)) {
                return "A tracked insertion carries deleted text inside w:ins.";
            }
            if (element instanceof RunDel) {
                return "A tracked insertion contains deleted run content.";
            }
        }
        return null;
    }

    private static boolean runHasDeletedText(R run) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof DelText) {
                return true;
            }
        }
        return false;
    }

    private static boolean runHasText(R run) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof Text || element instanceof DelText) {
                return true;
            }
        }
        return false;
    }

    private static boolean runHasNormalText(R run) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof Text) {
                return true;
            }
        }
        return false;
    }

    /**
     * Layer D: every revision id in the document must belong to exactly one reported
     * per-change range; ranges must be disjoint and must cover the document's ids.
     */
    private String rangeProblem(
            WordprocessingMLPackage wordPackage,
            DocumentExecutionPlan plan,
            List<AppliedChangeReport> appliedReports) {
        var reportByChange = new HashMap<UUID, AppliedChangeReport>();
        for (var report : appliedReports) {
            reportByChange.put(report.approvedChangeId(), report);
        }
        for (var operation : plan.operations()) {
            if (!reportByChange.containsKey(operation.approvedChangeId())) {
                return "The renderer did not report an applied change.";
            }
        }
        var reports = new ArrayList<>(reportByChange.values());
        for (var i = 0; i < reports.size(); i++) {
            for (var j = i + 1; j < reports.size(); j++) {
                var first = reports.get(i);
                var second = reports.get(j);
                if (first.revisionIdStart() <= second.revisionIdEnd()
                        && second.revisionIdStart() <= first.revisionIdEnd()) {
                    return "Per-change revision id ranges are not disjoint.";
                }
            }
        }
        for (var element : DocxRedlineSupport.revisionElements(
                wordPackage.getMainDocumentPart().getJaxbElement())) {
            if (element.getId() == null) {
                continue;
            }
            var id = element.getId().longValue();
            var covered = reports.stream().anyMatch(report ->
                    id >= report.revisionIdStart() && id <= report.revisionIdEnd());
            if (!covered) {
                return "The document contains a revision id outside the planned ranges.";
            }
        }
        for (var report : reports) {
            var planned = plan.operations().stream().anyMatch(operation ->
                    operation.approvedChangeId().equals(report.approvedChangeId()));
            if (!planned) {
                return "The renderer reported a change that is not part of the plan.";
            }
        }
        return null;
    }

    /**
     * One revision id per paragraph-action: the paragraph mark and all of its run
     * wrappers share the id; ids must not repeat across paragraphs.
     */
    private String duplicateRevisionIdProblem(WordprocessingMLPackage wordPackage) {
        var seen = new HashSet<Long>();
        var problem = new String[] {null};
        walk(wordPackage, (paragraph, table) -> {
            if (problem[0] != null || paragraph == null) {
                return;
            }
            var ids = new HashSet<Long>();
            collectParagraphRevisionIds(paragraph, ids);
            if (ids.size() > 1) {
                problem[0] = "A paragraph carries more than one revision id.";
                return;
            }
            for (var id : ids) {
                if (!seen.add(id)) {
                    problem[0] = "The generated document contains duplicate revision "
                            + "identifiers.";
                }
            }
        });
        return problem[0];
    }

    private void collectParagraphRevisionIds(P paragraph, HashSet<Long> ids) {
        var pPr = paragraph.getPPr();
        if (pPr != null) {
            if (pPr.getPPrChange() != null && pPr.getPPrChange().getId() != null) {
                ids.add(pPr.getPPrChange().getId().longValue());
            }
            if (pPr.getRPr() != null) {
                addRevisionId(pPr.getRPr().getIns(), ids);
                addRevisionId(pPr.getRPr().getDel(), ids);
            }
        }
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                if (run.getRPr() != null) {
                    addRevisionId(run.getRPr().getRPrChange(), ids);
                }
            } else if (element instanceof RunDel del) {
                addRevisionId(del, ids);
                collectWrappedRevisionIds(del.getCustomXmlOrSmartTagOrSdt(), ids);
            } else if (element instanceof RunIns ins) {
                addRevisionId(ins, ids);
                collectWrappedRevisionIds(ins.getCustomXmlOrSmartTagOrSdt(), ids);
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof RunDel del) {
                        addRevisionId(del, ids);
                        collectWrappedRevisionIds(del.getCustomXmlOrSmartTagOrSdt(), ids);
                    } else if (linkChild instanceof RunIns ins) {
                        addRevisionId(ins, ids);
                        collectWrappedRevisionIds(ins.getCustomXmlOrSmartTagOrSdt(), ids);
                    }
                }
            }
        }
    }

    private void collectWrappedRevisionIds(List<Object> wrapped, HashSet<Long> ids) {
        for (var raw : wrapped) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run && run.getRPr() != null) {
                addRevisionId(run.getRPr().getRPrChange(), ids);
            }
        }
    }

    private static void addRevisionId(CTTrackChange change, HashSet<Long> ids) {
        if (change != null && change.getId() != null) {
            ids.add(change.getId().longValue());
        }
    }

    private String partProblem(
            WordprocessingMLPackage source,
            WordprocessingMLPackage generated,
            byte[] sourceBytes,
            byte[] generatedBytes) {
        var sourceZip = zipEntries(sourceBytes);
        var generatedZip = zipEntries(generatedBytes);
        if (sourceZip == null || generatedZip == null) {
            return "The document packages could not be compared.";
        }
        for (var part : source.getParts().getParts().keySet()) {
            var name = part.getName().startsWith("/")
                    ? part.getName().substring(1) : part.getName();
            if (isPackageMetadata(name) || "word/document.xml".equals(name)) {
                continue;
            }
            var generatedPart = generatedZip.get(name);
            if (generatedPart == null) {
                return "A frozen source document part is missing from the generated redline.";
            }
            var sourcePart = sourceZip.get(name);
            if (sourcePart != null && !Arrays.equals(sourcePart, generatedPart)) {
                return "A frozen source document part changed during redline execution.";
            }
            var sourceContentType = contentType(source, name);
            var generatedContentType = contentType(generated, name);
            if (sourceContentType != null && !sourceContentType.equals(generatedContentType)) {
                return "A preserved document part changed its content type.";
            }
        }
        for (var name : generatedZip.keySet()) {
            if (!sourceZip.containsKey(name)) {
                return "The generated redline contains unexpected package content.";
            }
        }
        return relationshipProblem(source, generated);
    }

    /**
     * Every relationship of the frozen source must survive into the generated package
     * (targets, types, and external modes), including the regenerated relationship parts.
     */
    private String relationshipProblem(
            WordprocessingMLPackage source, WordprocessingMLPackage generated) {
        for (var entry : source.getParts().getParts().entrySet()) {
            if (!(entry.getValue() instanceof org.docx4j.openpackaging.parts.relationships
                    .RelationshipsPart sourceRelationships)) {
                continue;
            }
            var generatedPart = generated.getParts().getParts().get(entry.getKey());
            if (!(generatedPart instanceof org.docx4j.openpackaging.parts.relationships
                    .RelationshipsPart generatedRelationships)) {
                return "A frozen source relationship part is missing from the generated redline.";
            }
            var sourceList = sourceRelationships.getJaxbElement() == null
                    ? List.<org.docx4j.relationships.Relationship>of()
                    : sourceRelationships.getJaxbElement().getRelationship();
            var generatedList = generatedRelationships.getJaxbElement() == null
                    ? List.<org.docx4j.relationships.Relationship>of()
                    : generatedRelationships.getJaxbElement().getRelationship();
            for (var relationship : sourceList) {
                var present = generatedList.stream().anyMatch(candidate ->
                        Objects.equals(candidate.getType(), relationship.getType())
                                && Objects.equals(candidate.getTarget(),
                                        relationship.getTarget())
                                && Objects.equals(candidate.getTargetMode(),
                                        relationship.getTargetMode()));
                if (!present) {
                    return "A frozen source document relationship is missing from the "
                            + "generated redline.";
                }
            }
        }
        return null;
    }

    /**
     * Raw-XML revision count must equal the reachable revision tree: a property mark the
     * JAXB traversal cannot see (numberingChange, cellMerge, tblGridChange, ...) would
     * make the counts differ.
     */
    private String revisionCompletenessProblem(
            WordprocessingMLPackage generated, byte[] generatedBytes) {
        var rawCount = DocxRedlineSupport.revisionElementCount(generatedBytes,
                "word/document.xml");
        var reachableCount = DocxRedlineSupport.revisionElements(
                generated.getMainDocumentPart().getJaxbElement()).size();
        if (rawCount != reachableCount) {
            return "The generated document contains revision marks that cannot be "
                    + "attributed to the approved changes.";
        }
        return null;
    }

    /** Every revision mark must carry the execution author and the execution timestamp. */
    private String revisionProvenanceProblem(
            WordprocessingMLPackage generated, DocumentExecutionPlan plan) {
        for (var revision : DocxRedlineSupport.revisionElements(
                generated.getMainDocumentPart().getJaxbElement())) {
            if (!plan.author().equals(revision.getAuthor())) {
                return "A revision mark carries an unexpected author.";
            }
            if (revision.getDate() == null
                    || !revision.getDate().toGregorianCalendar().toInstant()
                            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                            .equals(plan.executedAt()
                                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                return "A revision mark carries an unexpected timestamp.";
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
            return null;
        }
        return entries;
    }

    private static String contentType(WordprocessingMLPackage wordPackage, String partName) {
        try {
            return wordPackage.getContentTypeManager()
                    .getContentType(new org.docx4j.openpackaging.parts.PartName("/" + partName));
        } catch (Exception e) {
            return null;
        }
    }
}
