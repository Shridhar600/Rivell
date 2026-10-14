package com.rivell.workbench.platform.parsing;

import java.util.List;

/**
 * Decides which block-level paragraphs of a document are its title and section headings.
 * Works on the whole document at once, because whether a line is a heading depends on
 * its neighbours.
 */
public interface HeadingDetector {

    /** One decision per paragraph, in the same order. */
    List<HeadingDecision> classify(List<ParagraphFeatures> paragraphs);
}
