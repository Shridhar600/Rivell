package com.rivell.workbench.review;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/** Persistence port for assembled findings and their children (run-scoped). */
public interface FindingRepository {

    /** Replaces one run's findings atomically (re-assembly); other runs are untouched. */
    void replaceFindings(
            UUID organizationId, UUID reviewId, UUID runId, List<Finding> findings);

    /**
     * Adds one finding to a completed run: a person's addition. The run's assembled findings
     * stay as they are; a later re-assembly of the same run never happens (runs complete once).
     */
    void addFinding(Finding finding);

    List<Finding> findFindings(UUID organizationId, UUID runId);

    /**
     * Counts one run's findings per classification without materialising them.
     *
     * <p>Diagnostics needs four integers. Reading them from {@link #findFindings} loads every
     * finding row for the run and then every concept and claim child, which is the most expensive
     * thing a stalled-run diagnostic can do, on the run most likely to be large. Classifications
     * absent from the result had no rows.
     */
    Map<FindingClassification, Long> countFindingsByClassification(
            UUID organizationId, UUID runId);

    /**
     * Bounded filtered list over one run, ordered by severity then consolidation key;
     * implementations read {@code limit + 1} rows for truncation. Children are not
     * loaded (list projection only).
     */
    List<Finding> findFindings(
            UUID organizationId, UUID runId, ConceptKey concept,
            FindingClassification classification, FindingSeverity severity, int limit);

    /** One finding of a run with concepts, claims, evidence ids, and signal ids. */
    Optional<Finding> findFinding(
            UUID organizationId, UUID reviewId, UUID runId, UUID findingId);

    /** Plan-check identity backing an absence finding (triage basis, MR4-A5). */
    Optional<AbsenceBasis> findAbsenceBasis(UUID organizationId, UUID runId, UUID findingId);

    int countFindings(UUID organizationId, UUID runId);

    /**
     * Counts what one generation proposes, per change kind.
     *
     * <p>A replacement is counted only when the Finding carries replacement wording, because
     * that is the proposal a reviewer can act on immediately. An insertion is counted when a
     * required provision is absent; Rivell detects that gap but proposes no wording for it, so
     * it is a detected gap rather than a drafted insertion. Deletion is never a machine
     * proposal and is always zero.
     *
     * <p>See {@link ProposedChangeCounts} for why the three numbers are not interchangeable.
     */
    ProposedChangeCounts countProposedChanges(UUID organizationId, UUID runId);

    record AbsenceBasis(UUID planCheckId, String checkKey) {
    }
}
