package com.rivell.workbench.review;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Code-owned contract pack: declares WHICH concepts are required/expected/optional and
 * which cross-clause checks apply. Clause-level policy comparison is not pack
 * configuration — every classified clause is compared against the published playbook.
 * Pack definitions own configuration; the engine owns how a check executes.
 */
public record ReviewPack(
        String key,
        String displayName,
        String summary,
        String version,
        Map<ConceptKey, CheckExpectation> conceptExpectations,
        List<CrossClauseRule> crossClauseChecks) {

    public ReviewPack {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(version, "version");
        conceptExpectations = Collections.unmodifiableMap(new LinkedHashMap<>(conceptExpectations));
        crossClauseChecks = List.copyOf(crossClauseChecks);
    }

    public record CrossClauseRule(
            CheckMechanism mechanism, ConceptKey concept, ConceptKey secondaryConcept) {

        public CrossClauseRule {
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(concept, "concept");
            Objects.requireNonNull(secondaryConcept, "secondaryConcept");
        }
    }
}
