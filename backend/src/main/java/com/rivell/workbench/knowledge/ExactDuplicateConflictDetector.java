package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic M2 duplicate detection: a candidate is flagged as an exact duplicate when
 * an already-published candidate of the same type carries the same raw label and the same
 * effective content. M4 replaces the interior with semantic analysis behind the same port.
 */
public class ExactDuplicateConflictDetector implements DuplicateConflictDetector {

    @Override
    public List<ConflictSignal> inspect(
            KnowledgeCandidate candidate, List<KnowledgeCandidate> publishedCandidates) {
        var signals = new ArrayList<ConflictSignal>();
        for (var published : publishedCandidates) {
            if (published.candidateType() != candidate.candidateType()) {
                continue;
            }
            if (sameText(published.rawLabel(), candidate.rawLabel())
                    && sameText(published.effectiveContentText(), candidate.effectiveContentText())) {
                signals.add(new ConflictSignal(
                        ConflictType.DUPLICATE,
                        "Matches published candidate with the same label and content",
                        published.id()));
            }
        }
        return List.copyOf(signals);
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }
}
