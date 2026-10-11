package com.rivell.workbench.taxonomy;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable identifier of a legal topic, e.g. {@code LIMITATION_OF_LIABILITY}. A key is only
 * well-formed; whether it exists is the {@link ConceptRegistry}'s call.
 */
public record ConceptKey(@JsonValue String value) implements Comparable<ConceptKey> {

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public ConceptKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Malformed concept key: " + value);
        }
    }

    @JsonCreator
    public static ConceptKey of(String value) {
        return new ConceptKey(value);
    }

    @Override
    public int compareTo(ConceptKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
