package com.rivell.workbench.retrieval;

import java.util.Map;
import java.util.UUID;

/**
 * Every applicable published playbook position of one contract for the omissions lane
 * (engine v2 P4), with the label of each candidate and whether the resolution hit its cap.
 * {@code bundle.policyItems()} carries the positions; the label travels here because an
 * evidence item has no label field (R-e).
 */
public record ApplicablePositions(EvidenceBundle bundle, Map<UUID, String> labels, boolean truncated) {

    public ApplicablePositions {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
