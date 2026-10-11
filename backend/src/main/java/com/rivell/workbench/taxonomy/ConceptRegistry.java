package com.rivell.workbench.taxonomy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, validated view of one taxonomy version, loaded once at startup. The taxonomy
 * changes only through a migration, so there is nothing to refresh at runtime.
 */
public final class ConceptRegistry {

    private final int version;
    private final Map<ConceptKey, Concept> byKey;
    private final List<Concept> leaves;
    private final List<Concept> parents;

    public ConceptRegistry(int version, List<Concept> concepts) {
        var ordered = concepts.stream().sorted(Comparator.comparingInt(Concept::sortOrder)).toList();
        var index = new LinkedHashMap<ConceptKey, Concept>();
        for (var concept : ordered) {
            if (index.put(concept.key(), concept) != null) {
                throw new IllegalStateException("Duplicate concept key " + concept.key());
            }
        }
        for (var concept : ordered) {
            if (concept.isLeaf()) {
                var parent = index.get(concept.parentKey());
                if (parent == null || parent.isLeaf()) {
                    throw new IllegalStateException(
                            "Concept " + concept.key() + " has no valid parent " + concept.parentKey());
                }
            }
        }
        this.version = version;
        this.byKey = Map.copyOf(index);
        this.leaves = ordered.stream().filter(Concept::isLeaf).toList();
        this.parents = ordered.stream().filter(concept -> !concept.isLeaf()).toList();
        if (leaves.isEmpty()) {
            throw new IllegalStateException("Taxonomy v" + version + " has no leaf concepts");
        }
    }

    public int version() {
        return version;
    }

    /** The concepts a section is classified against, in display order. */
    public List<Concept> leaves() {
        return leaves;
    }

    public List<Concept> parents() {
        return parents;
    }

    public Optional<Concept> find(ConceptKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Concept require(ConceptKey key) {
        var concept = byKey.get(key);
        if (concept == null) {
            throw new UnknownConceptException(key);
        }
        return concept;
    }

    public boolean contains(ConceptKey key) {
        return byKey.containsKey(key);
    }
}
