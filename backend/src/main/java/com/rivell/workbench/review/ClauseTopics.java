package com.rivell.workbench.review;

import java.util.LinkedHashSet;
import java.util.List;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * The topics a clause region effectively carries: the machine members minus the primary,
 * plus the effective primary (a human correction when one exists). Shared by the plan
 * builder (which clauses get a comparison) and the check (which positions match).
 */
public final class ClauseTopics {

    /**
     * The concept a plan check and its retrieval record for a clause with no topic: the
     * General family. No position or precedent carries it; only the uncovered-clause triage
     * reads such a clause.
     */
    public static final ConceptKey NO_TOPIC = ConceptKey.of("GENERAL");

    private ClauseTopics() {
    }

    public static List<ConceptKey> effectiveTopics(ClauseSemantics clause) {
        var topics = new LinkedHashSet<ConceptKey>();
        clause.effectivePrimaryConcept().ifPresent(topics::add);
        topics.addAll(clause.secondaryConcepts());
        return List.copyOf(topics);
    }

    /** The concept a plan check records: the effective primary, else the first topic. */
    public static ConceptKey subjectConcept(List<ConceptKey> effectiveTopics) {
        return effectiveTopics.isEmpty() ? NO_TOPIC : effectiveTopics.getFirst();
    }
}
