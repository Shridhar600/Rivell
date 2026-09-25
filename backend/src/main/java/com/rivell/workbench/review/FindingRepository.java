package com.rivell.workbench.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;

/** Persistence port for assembled findings and their children (run-scoped). */
public interface FindingRepository {

    /** Replaces one run's findings atomically (re-assembly); other runs are untouched. */
    void replaceFindings(
            UUID organizationId, UUID reviewId, UUID runId, List<Finding> findings);

    /** Deletes a run's findings (derived state) when its checks are re-executed. */
    void deleteFindings(UUID organizationId, UUID runId);

    List<Finding> findFindings(UUID organizationId, UUID runId);

    /**
     * Bounded filtered list over one run, ordered by severity then consolidation key;
     * implementations read {@code limit + 1} rows for truncation. Children are not
     * loaded (list projection only).
     */
    List<Finding> findFindings(
            UUID organizationId, UUID runId, LegalConcept concept,
            FindingClassification classification, FindingSeverity severity, int limit);

    /** One finding of a run with concepts, claims, evidence ids, and signal ids. */
    Optional<Finding> findFinding(
            UUID organizationId, UUID reviewId, UUID runId, UUID findingId);

    /** Plan-check identity backing an absence finding (triage basis, MR4-A5). */
    Optional<AbsenceBasis> findAbsenceBasis(UUID organizationId, UUID runId, UUID findingId);

    int countFindings(UUID organizationId, UUID runId);

    record AbsenceBasis(UUID planCheckId, String checkKey) {
    }
}
