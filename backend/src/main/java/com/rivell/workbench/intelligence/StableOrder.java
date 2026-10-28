package com.rivell.workbench.intelligence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic copies of caller-supplied collections. A source that guarantees iteration
 * order keeps it; any other source is sorted by name. Provider request bodies embed these
 * orders, and a per-JVM iteration order would make a recorded call impossible to replay in
 * another JVM.
 */
final class StableOrder {

    private StableOrder() {
    }

    static <K extends Comparable<? super K>, V> Map<K, V> map(Map<K, V> source) {
        Map<K, V> ordered = source instanceof SequencedMap<?, ?>
                ? new LinkedHashMap<>()
                : new TreeMap<>();
        for (var entry : source.entrySet()) {
            ordered.put(Objects.requireNonNull(entry.getKey(), "key"),
                    Objects.requireNonNull(entry.getValue(), "value"));
        }
        return Collections.unmodifiableMap(ordered);
    }

    static <E extends Comparable<? super E>> Set<E> set(Set<E> source) {
        Set<E> ordered = source instanceof SequencedSet<?>
                ? new LinkedHashSet<>()
                : new TreeSet<>();
        for (var element : source) {
            ordered.add(Objects.requireNonNull(element, "element"));
        }
        return Collections.unmodifiableSet(ordered);
    }
}
