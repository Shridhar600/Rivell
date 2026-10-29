package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.List;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * M4 duplicate/conflict/supersession analysis behind the M2 hook port.
 * Stage A (code) narrows plausible published peers by organization-scoped compatibility:
 * same candidate family, same primary concept, compatible contract types (declared and record
 * scope) and paper source, known compatible jurisdiction, and overlapping effective periods.
 * Stage B (code) detects exact duplicates. Stage C (code) resolves deterministic scope
 * overlap. Stage D (Jev) judges each narrowed peer pair for material contradiction and
 * supersession intent — machine proposals only; a human must approve any publication
 * action. Signals are advisory and never block.
 */
public class SemanticConflictAnalyzer implements DuplicateConflictDetector {

    private static final int MAX_PEERS = 5;

    private final SemanticDecisionService decisionService;
    private final SemanticPolicyProperties properties;

    public SemanticConflictAnalyzer(
            SemanticDecisionService decisionService, SemanticPolicyProperties properties) {
        this.decisionService = decisionService;
        this.properties = properties;
    }

    @Override
    public List<ConflictSignal> inspect(
            KnowledgeCandidate candidate, List<KnowledgeCandidate> publishedCandidates) {
        var conceptUnclassified = candidate.effectiveConceptKey() == null;
        var signals = new ArrayList<ConflictSignal>();
        var peers = narrowPeers(candidate, publishedCandidates, conceptUnclassified);

        for (var peer : peers) {
            if (isExactDuplicate(candidate, peer)) {
                signals.add(new ConflictSignal(
                        ConflictType.DUPLICATE,
                        "Matches a published entry with the same label, wording and scope.",
                        peer.id()));
                continue;
            }
            if (conceptUnclassified) {
                signals.add(new ConflictSignal(
                        ConflictType.UNKNOWN,
                        "CONFLICT_ANALYSIS_INCONCLUSIVE_CONCEPT_UNCLASSIFIED",
                        peer.id()));
                continue;
            }
            try {
                signals.addAll(semanticSignals(candidate, peer));
            } catch (SemanticDecisionException e) {
                signals.add(new ConflictSignal(
                        ConflictType.UNKNOWN,
                        "SEMANTIC_ANALYSIS_UNAVAILABLE",
                        peer.id()));
            }
        }
        return List.copyOf(signals);
    }

    private List<KnowledgeCandidate> narrowPeers(
            KnowledgeCandidate candidate, List<KnowledgeCandidate> published,
            boolean conceptUnclassified) {
        return published.stream()
                .filter(published0 -> published0.candidateType() == candidate.candidateType())
                .filter(published0 -> conceptUnclassified || sameConcept(published0, candidate))
                .filter(published0 -> compatibleContractTypes(published0, candidate))
                .filter(published0 -> compatiblePaperSource(published0, candidate))
                .filter(published0 -> compatibleJurisdiction(published0, candidate))
                .filter(published0 -> effectivePeriodsOverlap(published0, candidate))
                // oldest first, as the published read returns them; content breaks ties so a
                // fresh database selects the same peers rather than whichever random ids won.
                .sorted(java.util.Comparator
                        .comparing(KnowledgeCandidate::createdAt)
                        .thenComparing(KnowledgeCandidate::rawLabel)
                        .thenComparing(peer -> java.util.Objects.toString(
                                peer.effectiveContentText(), ""))
                        .thenComparing(peer -> java.util.Objects.toString(
                                peer.effectiveConceptKey(), ""))
                        .thenComparing(KnowledgeCandidate::id))
                .limit(MAX_PEERS)
                .toList();
    }

    private boolean isExactDuplicate(KnowledgeCandidate candidate, KnowledgeCandidate peer) {
        return sameText(peer.rawLabel(), candidate.rawLabel())
                && sameText(peer.effectiveContentText(), candidate.effectiveContentText())
                && Jurisdiction.same(peer.scope().jurisdiction(), candidate.scope().jurisdiction())
                && sameText(peer.scope().paperSource(), candidate.scope().paperSource())
                && periodBoundsEqual(peer.scope(), candidate.scope());
    }

    private static boolean periodBoundsEqual(ApplicabilityScope a, ApplicabilityScope b) {
        return java.util.Objects.equals(a.effectiveFrom(), b.effectiveFrom())
                && java.util.Objects.equals(a.effectiveTo(), b.effectiveTo());
    }

    private List<ConflictSignal> semanticSignals(KnowledgeCandidate candidate, KnowledgeCandidate peer) {
        var execution = decisionService.evaluate(
                M4SemanticSpecs.conflictRelationship(),
                new M4SemanticStates.ConflictPairState(
                        candidate.effectiveContentText(), roleSummary(candidate),
                        peer.effectiveContentText(), roleSummary(peer)));
        var signals = new ArrayList<ConflictSignal>();
        var contradicts = execution.answers().get("contradicts");
        var supersedes = execution.answers().get("supersedes");
        if (contradicts instanceof YesNoAnswer yesNo
                && yesNo.probability() >= properties.conflictProposal()) {
            signals.add(new ConflictSignal(
                    ConflictType.CONTRADICTS_EXISTING,
                    "May contradict a published position on the same topic and scope.",
                    peer.id()));
        }
        if (supersedes instanceof YesNoAnswer yesNo
                && yesNo.probability() >= properties.supersedeProposal()) {
            signals.add(new ConflictSignal(
                    ConflictType.SUPERSEDES_EXISTING,
                    "Looks like it replaces a published position on the same topic.",
                    peer.id()));
        }
        return signals;
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.strip().equalsIgnoreCase(b.strip());
    }

    private static String roleSummary(KnowledgeCandidate candidate) {
        var positions = candidate.effectivePositions();
        if (!positions.isEmpty()) {
            return positions.stream().map(Enum::name).sorted()
                    .reduce((a, b) -> a + "," + b).orElseThrow();
        }
        return candidate.effectiveVariantRole().name();
    }

    static boolean effectivePeriodsOverlap(KnowledgeCandidate a, KnowledgeCandidate b) {
        var aFrom = a.scope().effectiveFrom();
        var aTo = a.scope().effectiveTo();
        var bFrom = b.scope().effectiveFrom();
        var bTo = b.scope().effectiveTo();
        var aStartsBeforeBEnds = aFrom == null || bTo == null || !aFrom.isAfter(bTo);
        var bStartsBeforeAEnds = bFrom == null || aTo == null || !bFrom.isAfter(aTo);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    private static boolean compatibleContractTypes(KnowledgeCandidate a, KnowledgeCandidate b) {
        return ContractType.recordsMeetInAContractType(
                literalWording(a.candidateType()),
                a.scope().declaredContractTypes(), a.scope().sourceContractTypes(),
                b.scope().declaredContractTypes(), b.scope().sourceContractTypes());
    }

    /** A position states a general stance; literal wording is text written for one type. */
    private static boolean literalWording(CandidateType candidateType) {
        return candidateType == CandidateType.CLAUSE_VARIANT
                || candidateType == CandidateType.TEMPLATE_CLAUSE;
    }

    private static boolean compatibleJurisdiction(KnowledgeCandidate a, KnowledgeCandidate b) {
        return a.scope().jurisdiction() == null || b.scope().jurisdiction() == null
                || Jurisdiction.same(a.scope().jurisdiction(), b.scope().jurisdiction());
    }

    private static boolean compatiblePaperSource(KnowledgeCandidate a, KnowledgeCandidate b) {
        return a.scope().paperSource() == null || b.scope().paperSource() == null
                || a.scope().paperSource().equalsIgnoreCase(b.scope().paperSource());
    }

    private static boolean sameConcept(KnowledgeCandidate a, KnowledgeCandidate b) {
        return a.effectiveConceptKey() != null
                && a.effectiveConceptKey().equals(b.effectiveConceptKey());
    }
}
