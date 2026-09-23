package com.rivell.workbench.knowledge;

import java.util.List;

/**
 * Hook interface for duplicate/conflict detection before publication
 * (Architecture Decision #7.15). The deterministic M2 implementation raises exact
 * duplicate signals; M4 replaces the interior with semantic analysis. Signals are
 * advisory: they surface for human review and never block ingestion or publication.
 */
public interface DuplicateConflictDetector {

    List<ConflictSignal> inspect(KnowledgeCandidate candidate, List<KnowledgeCandidate> publishedCandidates);
}
