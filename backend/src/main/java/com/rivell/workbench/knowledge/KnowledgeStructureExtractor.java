package com.rivell.workbench.knowledge;

import java.io.InputStream;
import java.util.UUID;

/**
 * Port to the platform layer for deterministic structural extraction of a knowledge
 * source (DOCX or XLSX). Implementations preserve structure and provenance only — no
 * legal interpretation.
 */
public interface KnowledgeStructureExtractor {

    boolean supports(String filename);

    StructureExtraction extractStructure(UUID artifactId, InputStream source);
}
