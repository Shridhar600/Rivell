package com.rivell.workbench.taxonomy;

import java.util.List;

/**
 * One topic of the taxonomy. A parent groups leaves and has no parent or definition of its
 * own; only leaves are classified against.
 */
public record Concept(
        ConceptKey key,
        String label,
        ConceptKey parentKey,
        String definition,
        List<String> aliases,
        List<String> contractTypes,
        int sortOrder) {

    public Concept {
        if (key == null || label == null || label.isBlank()) {
            throw new IllegalArgumentException("A concept needs a key and a label");
        }
        if (parentKey != null && (definition == null || definition.isBlank())) {
            throw new IllegalArgumentException("Leaf concept " + key + " needs a definition");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        contractTypes = contractTypes == null ? List.of() : List.copyOf(contractTypes);
    }

    public boolean isLeaf() {
        return parentKey != null;
    }
}
