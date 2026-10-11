package com.rivell.workbench.review;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.retrieval.api.RetrievalResponses;

/**
 * The finding's own positions, grouped into the four slots the Review Workspace compares:
 * the contract's current position, the preferred position, the fallback positions, the
 * walk-away and the escalation condition.
 *
 * <p>This is a projection over the finding's already-frozen evidence. Retrieval is never
 * re-run, nothing is read from latest knowledge, and no position is invented: a slot that
 * the evidence does not support is empty, and the whole comparison is absent when no cited
 * evidence carries position data at all.
 *
 * <p>Authority and provenance travel with every slot, so a consumer can tell a playbook
 * position from a clause in the contract and open the exact frozen item behind either.
 * Authority is a property of the source and is copied through unchanged.
 *
 * <p>The three policy slots carry the authored wording verbatim, because a playbook position
 * is authored prose and no structured form of it exists to report instead. The contract slot
 * carries typed {@link LegalFact}s rather than a formatted sentence: the facts are the truth,
 * and a short display string would be a wording the backend has no authority to invent.
 *
 * <p>Slots are lists in the finding's own frozen evidence order. More than one cited item may
 * legitimately hold the same role, and dropping all but the first would hide evidence the
 * finding actually cites.
 */
public record FindingPositionComparison(
        List<ContractPositionSlot> contract,
        List<PolicyPositionSlot> preferred,
        List<PolicyPositionSlot> fallback,
        List<PolicyPositionSlot> walkAway,
        List<EscalationSlot> escalation) {

    public FindingPositionComparison {
        contract = contract == null ? List.of() : List.copyOf(contract);
        preferred = preferred == null ? List.of() : List.copyOf(preferred);
        fallback = fallback == null ? List.of() : List.copyOf(fallback);
        walkAway = walkAway == null ? List.of() : List.copyOf(walkAway);
        escalation = escalation == null ? List.of() : List.copyOf(escalation);
    }

    /** The contract's own position, as typed facts extracted from the frozen clause. */
    public record ContractPositionSlot(
            UUID evidenceItemId,
            String authorityClass,
            String legalConcept,
            List<LegalFact> facts) {

        public ContractPositionSlot {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    /** A playbook position, as authored, with the publication it was frozen from. */
    public record PolicyPositionSlot(
            UUID evidenceItemId,
            String authorityClass,
            String legalConcept,
            Long publicationNumber,
            String text) {
    }

    /** The authored condition under which the playbook position must be escalated. */
    public record EscalationSlot(
            UUID evidenceItemId,
            String authorityClass,
            Long publicationNumber,
            String trigger) {
    }

    /**
     * Groups the finding's frozen evidence into the comparison slots, preserving evidence
     * order. Absent data stays absent rather than being defaulted or back-filled.
     */
    public static FindingPositionComparison from(
            List<RetrievalResponses.EvidenceItemResponse> evidenceItems) {
        if (evidenceItems == null || evidenceItems.isEmpty()) {
            return null;
        }
        var contract = new ArrayList<ContractPositionSlot>();
        var preferred = new ArrayList<PolicyPositionSlot>();
        var fallback = new ArrayList<PolicyPositionSlot>();
        var walkAway = new ArrayList<PolicyPositionSlot>();
        var escalation = new ArrayList<EscalationSlot>();

        for (var item : evidenceItems) {
            if (item.contractFacts() != null && !item.contractFacts().isEmpty()) {
                contract.add(new ContractPositionSlot(
                        item.evidenceId(), item.authorityClass(), item.legalConcept(),
                        item.contractFacts()));
            }
            var policyFacts = item.policyFacts();
            if (policyFacts == null) {
                continue;
            }
            // role-tagged fields carry each stance's own wording; a record without them
            // carries the whole authored text in the slot its positions name
            if (policyFacts.preferred() != null) {
                preferred.add(slot(item, policyFacts.preferred()));
            } else if (carries(policyFacts, PositionRole.PREFERRED)) {
                preferred.add(slot(item, item.text()));
            }
            if (policyFacts.fallbacks() != null && !policyFacts.fallbacks().isEmpty()) {
                policyFacts.fallbacks().forEach(text -> fallback.add(slot(item, text)));
            } else if (carries(policyFacts, PositionRole.FALLBACK)) {
                fallback.add(slot(item, item.text()));
            }
            if (policyFacts.prohibited() != null) {
                walkAway.add(slot(item, policyFacts.prohibited()));
            }
            var trigger = policyFacts.escalationTrigger();
            if (trigger != null && !trigger.isBlank()) {
                escalation.add(new EscalationSlot(
                        item.evidenceId(), item.authorityClass(), item.publicationNumber(),
                        trigger));
            }
        }

        if (contract.isEmpty() && preferred.isEmpty() && fallback.isEmpty()
                && walkAway.isEmpty() && escalation.isEmpty()) {
            return null;
        }
        return new FindingPositionComparison(contract, preferred, fallback, walkAway, escalation);
    }

    private static PolicyPositionSlot slot(
            RetrievalResponses.EvidenceItemResponse item, String text) {
        return new PolicyPositionSlot(item.evidenceId(), item.authorityClass(),
                item.legalConcept(), item.publicationNumber(), text);
    }

    private static boolean carries(
            RetrievalResponses.PolicyFactsResponse policyFacts, PositionRole role) {
        return policyFacts.positions() != null && policyFacts.positions().contains(role.name());
    }
}