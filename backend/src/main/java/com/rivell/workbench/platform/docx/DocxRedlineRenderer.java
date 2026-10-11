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
import org.docx4j.wml.CTPPrChange;
import org.docx4j.wml.CTTrackChange;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.ParaRPr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Text;
import org.springframework.stereotype.Component;

import com.rivell.workbench.foundation.Hashes;
import com.rivell.workbench.platform.parsing.WordText;
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

    private static final String MAIN_DOCUMENT_ENTRY = "word/document.xml";

    private final com.rivell.workbench.platform.parsing.DocxParserProperties properties;

    public DocxRedlineRenderer(
            com.rivell.workbench.platform.parsing.DocxParserProperties properties) {
        this.properties = properties;
    }

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
        if (DocxRedlineSupport.hasPreexistingRevisions(sourceBytes)) {
            throw failure(RedlineFailureCode.PREEXISTING_REVISIONS_UNSUPPORTED, null,
                    "The source document already contains tracked revisions.");
        }

        var body = mainBody(wordPackage);
        var content = body.getContent();
        if (!allBodyContentRepresented(sourceBytes, content)) {
            // saving would silently drop the elements the package model could not read
            throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE, null,
                    "The source document has body content that cannot be carried into a redline.");
        }
        var paragraphByAnchor = new HashMap<String, P>();
        for (var ref : DocxRedlineSupport.paragraphs(content)) {
            paragraphByAnchor.put(anchorKey(ref.bodyIndex(), ref.paragraphIndex()),
                    ref.paragraph());
        }

        var nextId = new AtomicLong(DocxRedlineSupport.maxRevisionIdInBytes(sourceBytes) + 1);
        var date = DocxRedlineSupport.xmlDate(plan.executedAt());

        var resolved = new ArrayList<ResolvedOperation>();
        for (var operation : plan.operations()) {
            resolved.add(resolve(operation, paragraphByAnchor, content));
        }

        var tails = new HashMap<Object, Object>();
        var reports = new ArrayList<AppliedChangeReport>();
        for (var operation : resolved) {
            var start = nextId.get();
            if (operation.trimmed() != null) {
                trimHeading(operation.trimmed(), operation.operation(), nextId.getAndIncrement(),
                        plan.author(), date);
            }
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

        var bytes = serialize(wordPackage, sourceBytes);
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
            if (isBodyFinalParagraph(paragraph, content)
                    || carriesSectionProperties(paragraph)) {
                throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                        operation.approvedChangeId(),
                        "Approved change " + operation.ordinal()
                                + " would delete a paragraph whose mark cannot be removed "
                                + "as a tracked change.");
            }
            validateSupportedInline(paragraph, operation.approvedChangeId(),
                    operation.ordinal());
            targets.add(paragraph);
        }

        P trimmed = null;
        if (operation.headingTrim() != null) {
            var trim = operation.headingTrim();
            trimmed = paragraphByAnchor.get(anchorKey(trim.bodyIndex(), trim.paragraphIndex()));
            if (trimmed == null
                    || !DocxRedlineSupport.paragraphText(trimmed).equals(trim.expectedText())) {
                throw failure(RedlineFailureCode.TARGET_NOT_FOUND, operation.approvedChangeId(),
                        "Approved change " + operation.ordinal() + " (" + operation.actionType()
                                + ") cannot be resolved against the frozen document.");
            }
            validateSupportedInline(trimmed, operation.approvedChangeId(), operation.ordinal());
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
                if (operation.insertion().anchorExpectedText() != null) {
                    var anchorParagraph = paragraphByAnchor.get(anchorKey(after,
                            operation.insertion().anchorParagraphIndex()));
                    if (!(element instanceof P) || anchorParagraph == null
                            || !DocxRedlineSupport.paragraphText(anchorParagraph)
                                    .equals(operation.insertion().anchorExpectedText())) {
                        throw failure(RedlineFailureCode.TARGET_NOT_FOUND,
                                operation.approvedChangeId(),
                                "Approved change " + operation.ordinal()
                                        + " cannot be resolved against the frozen document.");
                    }
                    if (carriesSectionProperties(anchorParagraph)) {
                        throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                                operation.approvedChangeId(),
                                "Approved change " + operation.ordinal()
                                        + " anchors to a paragraph that carries section "
                                        + "properties.");
                    }
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
            } else if (trimmed != null) {
                // the clause had no body paragraph: new text takes the deleted clause text's
                // run formatting, not the heading's
                runTemplate = runPropertiesAfter(trimmed,
                        operation.headingTrim().keptHeading().length());
            }
        }
        return new ResolvedOperation(operation, targets, trimmed, rawAnchor, anchorKey,
                paragraphTemplate, runTemplate);
    }

    /**
     * Tracked-deletes the clause text after a run-in heading in the same paragraph. The heading
     * part stays untouched; a run that spans the boundary is split in two with the same
     * formatting. A hyperlink that spans it cannot be split deterministically and fails closed.
     */
    private void trimHeading(
            P paragraph, PlannedDocumentOperation operation, long revisionId, String author,
            XMLGregorianCalendar date) {
        var keep = operation.headingTrim().keptHeading().length();
        var rewritten = new ArrayList<Object>(paragraph.getContent().size() + 1);
        var offset = 0;
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (!(element instanceof R) && !(element instanceof P.Hyperlink)) {
                rewritten.add(raw);
                continue;
            }
            var length = WordText.childText(raw).length();
            if (offset + length <= keep) {
                rewritten.add(raw);
            } else if (offset >= keep) {
                rewritten.add(element instanceof R run
                        ? deletedRun(run, revisionId, author, date)
                        : deletedHyperlink((P.Hyperlink) element, revisionId, author, date));
            } else if (element instanceof R run) {
                var parts = split(run, keep - offset);
                rewritten.add(parts[0]);
                rewritten.add(deletedRun(parts[1], revisionId, author, date));
            } else {
                throw failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE,
                        operation.approvedChangeId(),
                        "Approved change " + operation.ordinal()
                                + " targets a clause whose heading and text share a hyperlink.");
            }
            offset += length;
        }
        paragraph.getContent().clear();
        paragraph.getContent().addAll(rewritten);
    }

    /** Two runs with the run's formatting: the text before {@code at} and the text after. */
    private R[] split(R run, int at) {
        var head = factory.createR();
        var tail = factory.createR();
        if (run.getRPr() != null) {
            head.setRPr(XmlUtils.deepCopy(run.getRPr()));
            tail.setRPr(XmlUtils.deepCopy(run.getRPr()));
        }
        var offset = 0;
        for (var raw : run.getContent()) {
            var length = WordText.childText(raw).length();
            if (offset + length <= at) {
                head.getContent().add(raw);
            } else if (offset >= at) {
                tail.getContent().add(raw);
            } else {
                var value = ((Text) DocxRedlineSupport.unwrap(raw)).getValue();
                head.getContent().add(text(value.substring(0, at - offset)));
                tail.getContent().add(text(value.substring(at - offset)));
            }
            offset += length;
        }
        return new R[] {head, tail};
    }

    private Text text(String value) {
        var text = factory.createText();
        text.setValue(value);
        text.setSpace("preserve");
        return text;
    }

    /** Formatting of the first run that carries text after {@code offset}. */
    private RPr runPropertiesAfter(P paragraph, int offset) {
        var position = 0;
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                position += WordText.childText(raw).length();
                if (position > offset) {
                    return run.getRPr();
                }
            } else if (element instanceof P.Hyperlink) {
                position += WordText.childText(raw).length();
            }
        }
        return null;
    }

    private void markDeleted(
            P paragraph, AtomicLong nextId, String author, XMLGregorianCalendar date) {
        var revisionId = nextId.getAndIncrement();
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
        paraRPr.setDel(trackChange(revisionId, author, date));

        var rewritten = new ArrayList<Object>(paragraph.getContent().size());
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element == pPr) {
                rewritten.add(raw);
            } else if (element instanceof R run) {
                rewritten.add(deletedRun(run, revisionId, author, date));
            } else if (element instanceof P.Hyperlink hyperlink) {
                rewritten.add(deletedHyperlink(hyperlink, revisionId, author, date));
            } else {
                rewritten.add(raw);
            }
        }
        paragraph.getContent().clear();
        paragraph.getContent().addAll(rewritten);
    }

    private RunDel deletedRun(
            R run, long revisionId, String author, XMLGregorianCalendar date) {
        var copy = XmlUtils.deepCopy(run);
        convertToDeletedText(copy.getContent());
        var del = factory.createRunDel();
        del.setId(BigInteger.valueOf(revisionId));
        del.setAuthor(author);
        del.setDate(date);
        del.getCustomXmlOrSmartTagOrSdt().add(copy);
        return del;
    }

    private P.Hyperlink deletedHyperlink(
            P.Hyperlink hyperlink, long revisionId, String author, XMLGregorianCalendar date) {
        var copy = XmlUtils.deepCopy(hyperlink);
        var rewritten = new ArrayList<Object>(copy.getContent().size());
        for (var raw : copy.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof R run) {
                rewritten.add(deletedRun(run, revisionId, author, date));
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
        var revisionId = nextId.getAndIncrement();
        var paragraph = factory.createP();
        var pPr = paragraphTemplate == null
                ? factory.createPPr() : XmlUtils.deepCopy(paragraphTemplate);
        pPr.setRPr(null);
        pPr.setSectPr(null);
        pPr.setPPrChange(null);
        if (pPr.getNumPr() != null) {
            pPr.getNumPr().setNumberingChange(null);
        }
        var paraRPr = factory.createParaRPr();
        paraRPr.setIns(trackChange(revisionId, author, date));
        pPr.setRPr(paraRPr);
        paragraph.setPPr(pPr);

        if (!text.isEmpty()) {
            var run = factory.createR();
            if (runTemplate != null) {
                var copiedRPr = XmlUtils.deepCopy(runTemplate);
                copiedRPr.setRPrChange(null);
                run.setRPr(copiedRPr);
            }
            var textElement = factory.createText();
            textElement.setValue(text);
            textElement.setSpace("preserve");
            run.getContent().add(textElement);
            var ins = factory.createRunIns();
            ins.setId(BigInteger.valueOf(revisionId));
            ins.setAuthor(author);
            ins.setDate(date);
            ins.getCustomXmlOrSmartTagOrSdt().add(run);
            paragraph.getContent().add(ins);
        }
        return paragraph;
    }

    private CTTrackChange trackChange(
            long revisionId, String author, XMLGregorianCalendar date) {
        var change = factory.createCTTrackChange();
        change.setId(BigInteger.valueOf(revisionId));
        change.setAuthor(author);
        change.setDate(date);
        return change;
    }

    private boolean isBodyFinalParagraph(P paragraph, List<Object> content) {
        var refs = DocxRedlineSupport.paragraphs(content);
        return refs.stream().anyMatch(ref -> ref.paragraph() == paragraph
                && ref.paragraphIndex() == refs.size() - 1);
    }

    private boolean carriesSectionProperties(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getSectPr() != null;
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
        if (!TrackableContent.paragraph(paragraph)) {
            throw unsupported(approvedChangeId, ordinal);
        }
    }

    private static boolean allBodyContentRepresented(byte[] sourceBytes, List<Object> content) {
        try {
            var mainXml = DocxRedlineSupport.entryBytes(sourceBytes, "word/document.xml");
            return com.rivell.workbench.platform.parsing.ZipGuard.countTopLevelBodyChildren(mainXml)
                    == content.size();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private RedlineRenderException unsupported(UUID approvedChangeId, int ordinal) {
        return failure(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE, approvedChangeId,
                "Approved change " + ordinal + " targets document content that cannot be "
                        + "executed deterministically.");
    }

    private org.docx4j.wml.Body mainBody(WordprocessingMLPackage wordPackage) {
        try {
            var document = wordPackage.getMainDocumentPart().getJaxbElement();
            var body = document == null ? null : document.getBody();
            if (body == null) {
                throw new IllegalStateException("missing main document body");
            }
            return body;
        } catch (RuntimeException e) {
            throw failure(RedlineFailureCode.DOCX_LOAD_FAILED, null,
                    "The source document could not be loaded for execution.");
        }
    }

    /**
     * Loads the source with the same decompression bound the ingest parser applies, before docx4j
     * materialises the package. Without it the redline path is the one place in the system where a
     * crafted package reaches the heap unbounded, and it is reachable with stored bytes that were
     * never re-checked after ingestion.
     */
    private WordprocessingMLPackage load(byte[] sourceBytes) {
        try {
            com.rivell.workbench.platform.parsing.ZipGuard.verify(sourceBytes,
                    properties.maxUncompressedBytes());
            return WordprocessingMLPackage.load(new ByteArrayInputStream(sourceBytes));
        } catch (com.rivell.workbench.platform.parsing.ZipGuard.ZipGuardException e) {
            throw failure(RedlineFailureCode.SOURCE_EXPANSION_LIMIT_EXCEEDED, null,
                    "The source document expands beyond the permitted size for execution.");
        } catch (Exception e) {
            throw failure(RedlineFailureCode.DOCX_LOAD_FAILED, null,
                    "The source document could not be loaded for execution.");
        }
    }

    /**
     * The source package with only the main document part replaced. docx4j re-serializes every
     * part whose JAXB tree was read (the settings part is read for the protection check) and drops
     * markup its schema rejects, so saving the whole package would change parts the redline never
     * edits. Copying them from the source keeps them byte-identical.
     */
    private byte[] serialize(WordprocessingMLPackage wordPackage, byte[] sourceBytes) {
        try (var output = new ByteArrayOutputStream()) {
            wordPackage.save(output);
            var document = DocxRedlineSupport.entryBytes(output.toByteArray(), MAIN_DOCUMENT_ENTRY);
            return DocxRedlineSupport.withEntryReplaced(sourceBytes, MAIN_DOCUMENT_ENTRY, document);
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
            P trimmed,
            Object rawAnchor,
            Object anchorKey,
            PPr paragraphTemplate,
            RPr runTemplate) {
    }
}
