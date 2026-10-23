package com.rivell.workbench.contracts;

import java.util.Locale;

public enum Jurisdiction {
    US,
    UK,
    AUSTRALIA,
    CANADA,
    IRELAND,
    INDIA,
    NEW_ZEALAND,
    SINGAPORE,
    HONG_KONG,
    GERMANY,
    FRANCE,
    NETHERLANDS,
    OTHER,
    UNSPECIFIED;

    /**
     * One equality form for jurisdiction names regardless of how they were typed:
     * lower case with non-alphanumerics removed, plus the two long-form aliases the
     * product already displays. Null stays null so callers can treat it as a wildcard.
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "uk", "unitedkingdom" -> "uk";
            case "us", "unitedstates", "unitedstatesofamerica", "usa" -> "us";
            default -> normalized;
        };
    }

    public static boolean same(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return normalize(left).equals(normalize(right));
    }
}
