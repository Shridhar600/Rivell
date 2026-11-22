package com.rivell.workbench.knowledge;

import java.util.List;

/**
 * Hook interface for duplicate/conflict detection before publication
 * (Architecture Decision #7.15). Signals are advisory: they surface for human review
 * and never block ingestion or publication.
 */
public interface DuplicateConflictDetector {

    List<ConflictSignal> inspect(KnowledgeCandidate candidate, List<KnowledgeCandidate> publishedCandidates);
}
