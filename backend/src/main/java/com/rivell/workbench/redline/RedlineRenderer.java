package com.rivell.workbench.redline;

/**
 * Deterministic DOCX renderer port. Implementations live in platform (docx4j); the
 * domain module never sees OpenXML types. Rendering must be atomic: either the whole
 * plan is applied or a typed failure is raised with no output.
 */
public interface RedlineRenderer {

    RenderedRedline render(byte[] sourceBytes, DocumentExecutionPlan plan);
}
