package com.rivell.workbench.contracts;

import java.util.Map;
import java.util.Set;

/**
 * The bounded, versioned fact-shape registry. Each schema names its typed fields and
 * their validation rules; unknown fields are rejected (no unbounded EAV). Values are
 * string-encoded and validated here — downstream comparisons are deterministic code
 * over typed facts, never a model call.
 */
public enum FactSchema {

    LIABILITY_FACTS("liability-facts", 1,
            Set.of("liabilityCapMultiple", "capBasis",
                    "consequentialDamagesExcluded", "dataBreachCarveout")) {

        @Override
        void validate(String field, String value) {
            switch (field) {
                case "liabilityCapMultiple" -> positiveDecimal(value);
                case "capBasis" -> oneOf(value, "PRECEDING_12_MONTH_FEES", "ANNUAL_FEES",
                        "UNSPECIFIED");
                case "consequentialDamagesExcluded", "dataBreachCarveout" -> booleanValue(value);
                default -> throw new IllegalArgumentException("Unknown field " + field);
            }
        }
    },

    TERMINATION_FACTS("termination-facts", 1,
            Set.of("noticePeriodDays", "availableTo", "terminationCharge",
                    "feesDueOnTermination")) {

        @Override
        void validate(String field, String value) {
            switch (field) {
                case "noticePeriodDays" -> nonNegativeInteger(value);
                case "availableTo" -> oneOf(value, "ANY_PARTY", "CUSTOMER", "SUPPLIER",
                        "UNSPECIFIED");
                case "terminationCharge", "feesDueOnTermination" -> booleanValue(value);
                default -> throw new IllegalArgumentException("Unknown field " + field);
            }
        }
    };

    private final String id;
    private final int version;
    private final Set<String> fields;

    FactSchema(String id, int version, Set<String> fields) {
        this.id = id;
        this.version = version;
        this.fields = fields;
    }

    public String id() {
        return id;
    }

    public int version() {
        return version;
    }

    public Set<String> fields() {
        return fields;
    }

    public static FactSchema byId(String id) {
        for (var schema : values()) {
            if (schema.id.equals(id)) {
                return schema;
            }
        }
        throw new IllegalArgumentException("Unknown fact schema " + id);
    }

    abstract void validate(String field, String value);

    static void nonNegativeInteger(String value) {
        try {
            if (Integer.parseInt(value) < 0) {
                throw new IllegalArgumentException("Value must not be negative: " + value);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value is not an integer: " + value);
        }
    }

    static void positiveDecimal(String value) {
        try {
            if (new java.math.BigDecimal(value).signum() <= 0) {
                throw new IllegalArgumentException("Value must be positive: " + value);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value is not a number: " + value);
        }
    }

    static void booleanValue(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Value is not a boolean: " + value);
        }
    }

    static void oneOf(String value, String... options) {
        for (var option : options) {
            if (option.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("Value " + value + " is not one of "
                + String.join(", ", options));
    }
}
