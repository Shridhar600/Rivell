package com.rivell.workbench.platform.docx;

import org.docx4j.wml.Br;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.CTMarkupRange;
import org.docx4j.wml.CTPerm;
import org.docx4j.wml.CommentRangeEnd;
import org.docx4j.wml.CommentRangeStart;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.ProofErr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Text;

/**
 * The inline content a tracked change can delete deterministically: text, breaks, tabs and
 * symbols in runs, optionally inside hyperlinks, plus markup that carries no content (bookmarks,
 * comment ranges, proofing marks). A footnote reference, field, drawing or embedded object is
 * not. The renderer refuses a change over anything else; the parser records the same rule per
 * paragraph so such a change is refused before it is approved.
 */
public final class TrackableContent {

    private TrackableContent() {
    }

    public static boolean paragraph(P paragraph) {
        for (var raw : paragraph.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (element instanceof PPr || markup(element) || element instanceof CTPerm) {
                continue;
            }
            if (element instanceof R run) {
                if (!run(run)) {
                    return false;
                }
            } else if (element instanceof P.Hyperlink hyperlink) {
                for (var rawLinkChild : hyperlink.getContent()) {
                    var linkChild = DocxRedlineSupport.unwrap(rawLinkChild);
                    if (linkChild instanceof R run ? !run(run) : !markup(linkChild)) {
                        return false;
                    }
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean markup(Object element) {
        return element instanceof ProofErr || element instanceof CTBookmark
                || element instanceof CTMarkupRange || element instanceof CommentRangeStart
                || element instanceof CommentRangeEnd;
    }

    private static boolean run(R run) {
        for (var raw : run.getContent()) {
            var element = DocxRedlineSupport.unwrap(raw);
            if (!(element instanceof Text || element instanceof Br || element instanceof R.Cr
                    || element instanceof R.Tab || element instanceof R.Sym
                    || element instanceof R.SoftHyphen || element instanceof R.NoBreakHyphen
                    || element instanceof R.LastRenderedPageBreak
                    || element instanceof R.Ptab || element instanceof RPr)) {
                return false;
            }
        }
        return true;
    }
}
