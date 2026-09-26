package com.rivell.workbench.platform.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.datatype.XMLGregorianCalendar;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.CTMarkupRange;
import org.docx4j.wml.CTPerm;
import org.docx4j.wml.CTPPrChange;
import org.docx4j.wml.CTTrackChange;
import org.docx4j.wml.CommentRangeEnd;
import org.docx4j.wml.CommentRangeStart;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.ParaRPr;
import org.docx4j.wml.ProofErr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Text;
import org.springframework.stereotype.Component;

import com.rivell.workbench.foundation.Hashes;
import com.rivell.workbench.redline.AppliedChangeReport;
import com.rivell.workbench.redline.DocumentExecutionPlan;
import com.rivell.workbench.redline.PlannedDocumentOperation;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineRenderException;
import com.rivell.workbench.redline.RedlineRenderer;
import com.rivell.workbench.redline.RenderedRedline;

/**
 * Deterministic docx4j renderer: applies an already-resolved plan as Word-standard
 * tracked changes. All anchors resolve against the untouched source before any
 * mutation; policy checks fail closed; no legal reasoning or model access.
 */
@Component
public class DocxRedlineRenderer implements RedlineRenderer {

    private static final Object START_OF_BODY = new Object();

    private final ObjectFactory factory = new ObjectFactory();

    @Override
    public RenderedRedline render(byte[] sourceBytes, DocumentExecutionPlan plan) {
        var wordPackage = load(sourceBytes);
        if (!DocxRedlineSupport.isDocxMainPart(wordPackage)) {
            throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_FORMAT, null,
                    "Only tracked-change-capable DOCX documents can be executed.");
        }
        if (DocxRedlineSupport.isProtected(wordPackage)) {
            throw failure(RedlineFailureCode.PROTECTED_DOCUMENT_UNSUPPORTED, null,
                    "The source document enforces editing protection.");
        }
        if (DocxRedlineSupport.hasPreexistingRevisions(wordPackage)) {
            throw failure(RedlineFailureCode.PREEXISTING_REVISIONS_UNSUPPORTED, null,
                    "The source document already contains tracked revisions.");
        }

        var body = DocxRedlineSupport.body(wordPackage);
        var content = body.getContent();
        var paragraphByAnchor = new HashMap<String, P>();
        for (var ref : DocxRedlineSupport.paragraphs(content)) {
            paragraphByAnchor.put(anchorKey(ref.bodyIndex(), ref.paragraphIndex()),
                    ref.paragraph());
        }

        var nextId = new AtomicLong(DocxRedlineSupport.maxRevisionId(wordPackage) + 1);
        var date = DocxRedlineSupport.xmlDate(plan.executedAt());

        var resolved = new ArrayList<ResolvedOperation>();
        for (var operation : plan.operations()) {
            resolved.add(resolve(operation, paragraphByAnchor, content));
        }

        var tails = new HashMap<Object, Object>();
        var reports = new ArrayList<AppliedChangeReport>();
        for (var operation : resolved) {
            var start = nextId.get();
            for (var target : operation.targets()) {
                markDeleted(target, nextId, plan.author(), date);
            }
            if (operation.operation().insertion() != null) {
                var insertion = operation.operation().insertion();
                var tail = tails.get(operation.anchorKey());
                for (var paragraphText : insertion.paragraphs()) {
                    var inserted = insertedParagraph(paragraphText, nextId, plan.author(), date,
                            operation.paragraphTemplate(), operation.runTemplate());
                    var index = tail != null
                            ? content.indexOf(tail) + 1
                            : insertion.afterBodyIndex() < 0
                                    ? 0
                                    : content.indexOf(operation.rawAnchor()) + 1;
                    content.add(index, inserted);
                    tail = inserted;
                }
                tails.put(operation.anchorKey(), tail);
            }
            reports.add(new AppliedChangeReport(operation.operation().approvedChangeId(),
                    operation.operation().ordinal(), operation.targets().size(),
                    operation.operation().insertion() == null
                            ? 0 : operation.operation().insertion().paragraphs().size(),
                    start, nextId.get() - 1));
        }

        var bytes = serialize(wordPackage);
        return new RenderedRedline(bytes, Hashes.sha256Hex(bytes), reports);
    }

    private ResolvedOperation resolve(
            PlannedDocumentOperation operation,
            Map<String, P> paragraphByAnchor,
            List<Object> content) {
        var targets = new ArrayList<P>();
        for (var target : operation.deletionTargets()) {
            var paragraph = paragraphByAnchor.get(
                    anchorKey(target.bodyIndex(), target.paragraphIndex()));
            if (paragraph == null
                    || !DocxRedlineSupport.paragraphText(paragraph).equals(target.expectedText())) {
                throw failure(RedlineFailureCode.TARGET_NOT_FOUND, operation.approvedChangeId(),
                        "Approved change " + operation.ordinal() + " (" + operation.actionType()
                                + ") cannot be resolved against the frozen document.");
            }
            validateSupportedInline(paragraph, operation.approvedChangeId(),
                    operation.ordinal());
            targets.add(paragraph);
        }

        Object rawAnchor = null;
        Object anchorKey = null;
        PPr paragraphTemplate = null;
        RPr runTemplate = null;
        if (operation.insertion() != null) {
            var after = operation.insertion().afterBodyIndex();
            if (after < 0) {
                anchorKey = START_OF_BODY;
            } else {
                if (after >= content.size()) {
                    throw failure(RedlineFailureCode.TARGET_NOT_FOUND,
                            operation.approvedChangeId(),
                            "Approved change " + operation.ordinal()
                                    + " cannot be resolved against the frozen document.");
                }
                rawAnchor = content.get(after);
                var element = DocxRedlineSupport.unwrap(rawAnchor);
                if (!(element instanceof P || element instanceof Tbl)) {
                    throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                            operation.approvedChangeId(),
                            "Approved change " + operation.ordinal()
                                    + " anchors to unsupported document content.");
                }
                anchorKey = rawAnchor;
            }
            if (operation.insertion().styleTemplateBodyIndex() != null) {
                var templateParagraph = paragraphByAnchor.get(anchorKey(
                        operation.insertion().styleTemplateBodyIndex(),
                        operation.insertion().styleTemplateParagraphIndex()));
                if (templateParagraph == null) {
                    throw failure(RedlineFailureCode.TARGET_NOT_FOUND,
                            operation.approvedChangeId(),
                            "Approved change " + operation.ordinal()
                                    + " cannot be resolved against the frozen document.");
                }
                validateSupportedInline(templateParagraph, operation.approvedChangeId(),
                        operation.ordinal());
                paragraphTemplate = templateParagraph.getPPr();
                runTemplate = firstRunProperties(templateParagraph);
            }
        }
        return new ResolvedOperation(operation, targets, rawAnchor, anchorKey,
                paragraphTemplate, runTemplate);
    }

    private void markDeleted(
            P paragraph, AtomicLong nextId, String author, XMLGregorianCalendar date) {
        var pPr = paragraph.getPPr();
        if (pPr == null) {
            pPr = factory.createPPr();
            paragraph.getContent().add(0, pPr);
        }
        var paraRPr = pPr.getRPr();
        if (paraRPr == null) {
            paraRPr = factory.createParaRPr();
            pPr.setRPr(paraRPr);
        }
        paraRPr.setDel(trackChange(nextId, author, date));

        var rewritten = new ArrayList<Object>(paragraph.getContent().size());
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element == pPr) {
                rewritten.add(raw);
            } else if (element instanceof R run) {
                rewritten.add(deletedRun(run, nextId, author, date));
            } else if (element instanceof P.Hyperlink hyperlink) {
                rewritten.add(deletedHyperlink(hyperlink, nextId, author, date));
            } else {
                rewritten.add(raw);
            }
        }
        paragraph.getContent().clear();
        paragraph.getContent().addAll(rewritten);
    }

    private RunDel deletedRun(
            R run, AtomicLong nextId, String author, XMLGregorianCalendar date) {
        var copy = XmlUtils.deepCopy(run);
        convertToDeletedText(copy.getContent());
        var del = factory.createRunDel();
        del.setId(BigInteger.valueOf(nextId.getAndIncrement()));
        del.setAuthor(author);
        del.setDate(date);
        del.getCustomXmlOrSmartTagOrSdt().add(copy);
        return del;
    }

    private P.Hyperlink deletedHyperlink(
            P.Hyperlink hyperlink, AtomicLong nextId, String author, XMLGregorianCalendar date) {
        var copy = XmlUtils.deepCopy(hyperlink);
        var rewritten = new ArrayList<Object>(copy.getContent().size());
        for (var raw : copy.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                rewritten.add(deletedRun(run, nextId, author, date));
            } else {
                rewritten.add(raw);
            }
        }
        copy.getContent().clear();
        copy.getContent().addAll(rewritten);
        return copy;
    }

    private static void convertToDeletedText(List<Object> content) {
        for (var i = 0; i < content.size(); i++) {
            var value = DocxRedlineSupport.unwrap(content.get(i));
            if (value instanceof Text text) {
                var deleted = new ObjectFactory().createDelText();
                deleted.setValue(text.getValue());
                deleted.setSpace(text.getSpace());
                content.set(i, deleted);
            }
        }
    }

    private P insertedParagraph(
            String text,
            AtomicLong nextId,
            String author,
            XMLGregorianCalendar date,
            PPr paragraphTemplate,
            RPr runTemplate) {
        var paragraph = factory.createP();
        var pPr = paragraphTemplate == null
                ? factory.createPPr() : XmlUtils.deepCopy(paragraphTemplate);
        pPr.setRPr(null);
        pPr.setSectPr(null);
        pPr.setPPrChange(null);
        var paraRPr = factory.createParaRPr();
        paraRPr.setIns(trackChange(nextId, author, date));
        pPr.setRPr(paraRPr);
        paragraph.setPPr(pPr);

        if (!text.isEmpty()) {
            var run = factory.createR();
            if (runTemplate != null) {
                run.setRPr(XmlUtils.deepCopy(runTemplate));
            }
            var textElement = factory.createText();
            textElement.setValue(text);
            textElement.setSpace("preserve");
            run.getContent().add(textElement);
            var ins = factory.createRunIns();
            ins.setId(BigInteger.valueOf(nextId.getAndIncrement()));
            ins.setAuthor(author);
            ins.setDate(date);
            ins.getCustomXmlOrSmartTagOrSdt().add(run);
            paragraph.getContent().add(ins);
        }
        return paragraph;
    }

    private CTTrackChange trackChange(
            AtomicLong nextId, String author, XMLGregorianCalendar date) {
        var change = factory.createCTTrackChange();
        change.setId(BigInteger.valueOf(nextId.getAndIncrement()));
        change.setAuthor(author);
        change.setDate(date);
        return change;
    }

    private RPr firstRunProperties(P paragraph) {
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run && run.getRPr() != null) {
                return run.getRPr();
            }
            if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run && run.getRPr() != null) {
                        return run.getRPr();
                    }
                }
            }
        }
        return null;
    }

    private void validateSupportedInline(P paragraph, UUID approvedChangeId, int ordinal) {
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof PPr || element instanceof ProofErr
                    || element instanceof CTBookmark || element instanceof CTMarkupRange
                    || element instanceof CTPerm || element instanceof CommentRangeStart
                    || element instanceof CommentRangeEnd) {
                continue;
            }
            if (element instanceof R run) {
                validateRun(run, approvedChangeId, ordinal);
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run) {
                        validateRun(run, approvedChangeId, ordinal);
                    } else if (!(linkChild instanceof ProofErr
                            || linkChild instanceof CTBookmark
                            || linkChild instanceof CTMarkupRange
                            || linkChild instanceof CommentRangeStart
                            || linkChild instanceof CommentRangeEnd)) {
                        throw unsupported(approvedChangeId, ordinal);
                    }
                }
            } else {
                throw unsupported(approvedChangeId, ordinal);
            }
        }
    }

    private void validateRun(R run, UUID approvedChangeId, int ordinal) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof Text || element instanceof Br || element instanceof R.Cr
                    || element instanceof R.Tab || element instanceof R.Sym
                    || element instanceof R.SoftHyphen || element instanceof R.NoBreakHyphen
                    || element instanceof R.LastRenderedPageBreak
                    || element instanceof R.Ptab || element instanceof RPr) {
                continue;
            }
            throw unsupported(approvedChangeId, ordinal);
        }
    }

    private RedlineRenderException unsupported(UUID approvedChangeId, int ordinal) {
        return failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE, approvedChangeId,
                "Approved change " + ordinal + " targets document content that cannot be "
                        + "executed deterministically.");
    }

    private WordprocessingMLPackage load(byte[] sourceBytes) {
        try {
            return WordprocessingMLPackage.load(new ByteArrayInputStream(sourceBytes));
        } catch (Exception e) {
            throw failure(RedlineFailureCode.DOCX_LOAD_FAILED, null,
                    "The source document could not be loaded for execution.");
        }
    }

    private byte[] serialize(WordprocessingMLPackage wordPackage) {
        try (var output = new ByteArrayOutputStream()) {
            wordPackage.save(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw failure(RedlineFailureCode.DOCX_SERIALIZATION_FAILED, null,
                    "The redline document could not be serialized.");
        }
    }

    private static String anchorKey(int bodyIndex, int paragraphIndex) {
        return bodyIndex + ":" + paragraphIndex;
    }

    private static RedlineRenderException failure(
            RedlineFailureCode code, UUID approvedChangeId, String message) {
        return new RedlineRenderException(code, approvedChangeId, message);
    }

    private record ResolvedOperation(
            PlannedDocumentOperation operation,
            List<P> targets,
            Object rawAnchor,
            Object anchorKey,
            PPr paragraphTemplate,
            RPr runTemplate) {
    }
}
