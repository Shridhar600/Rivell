package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/**
 * Deterministic compilation of a parsed knowledge source into extraction units and typed
 * candidates. Deterministic only (version {@link #COMPILER_VERSION}); semantic
 * interpretation happens downstream.
 */
public final class KnowledgeCompiler {

    public static final String COMPILER_VERSION = "rivell-knowledge-compiler-2";

    private KnowledgeCompiler() {
    }

    public record CompiledKnowledge(
            List<ExtractionUnit> units,
            List<KnowledgeCandidate> candidates,
            List<String> warnings) {
    }

    /**
     * Compiles a structural extraction into extraction units and typed candidates.
     * DOCX structure yields heading sections and tables; spreadsheet structure yields
     * header-aware row units. Units without textual content produce no candidate.
     */
    public static CompiledKnowledge compile(
            KnowledgeArtifact artifact, List<KnowledgeSourceNode> nodes) {
        var structure = KnowledgeStructure.build(nodes);
        var units = new ArrayList<ExtractionUnit>();
        var candidates = new ArrayList<KnowledgeCandidate>();
        var now = Instant.now();
        var scope = ApplicabilityScope.fromManifest(artifact.manifest());

        int ordinal = 0;
        for (KnowledgeStructure.Region region : structure.regions()) {
            var unit = region.toUnit(artifact.id(), ordinal++);
            units.add(unit);
            var compiled = region.toCandidates(artifact, unit, scope, now);
            candidates.addAll(compiled);
        }
        return new CompiledKnowledge(List.copyOf(units), List.copyOf(candidates), List.of());
    }
}
