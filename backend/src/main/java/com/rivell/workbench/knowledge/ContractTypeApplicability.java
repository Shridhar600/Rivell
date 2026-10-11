package com.rivell.workbench.knowledge;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.rivell.workbench.contracts.ContractType;

/**
 * Reads what a playbook record says it applies to ("Applies to: MSA, Supply, Services.", a
 * contract-type column group) as contract type codes. Deterministic. A named type the
 * product does not model ("Supply") maps to OTHER, so the record still applies to contracts
 * of that unmodelled kind and not to every modelled type. Text that names no type, says
 * "all", or excludes types returns empty: the record then applies wherever its source does.
 */
final class ContractTypeApplicability {

    /** A prose line that states applicability: "Applies to: MSA, SOW." */
    private static final Pattern STATEMENT = Pattern.compile(
            "(?im)^\\s*(?:applies to|applicable to|contract types?)\\s*[:\\-\u2013\u2014]\\s*(.+?)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:[,;/|&+\\n]|\\band\\b|\\bor\\b)\\s*");
    private static final Pattern NO_NARROWING = Pattern.compile(
            "\\b(?:all|any|every|general|except|excluding|other than|unless|not)\\b");

    private ContractTypeApplicability() {
    }

    /** The applicability a prose record states on its own line, if any. */
    static Optional<String> statedIn(String proseText) {
        if (proseText == null) {
            return Optional.empty();
        }
        var matcher = STATEMENT.matcher(proseText);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    static Set<String> contractTypes(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var normalized = text.toLowerCase(Locale.ROOT).replaceAll("[.\u2019']", "").strip();
        if (NO_NARROWING.matcher(normalized).find()) {
            return Set.of();
        }
        var types = new LinkedHashSet<String>();
        for (var token : SEPARATOR.split(normalized)) {
            var name = token.replaceAll("[^a-z0-9 -]", " ").replaceAll("\\s+", " ").strip();
            if (!name.isEmpty()) {
                types.add(typeOf(name).name());
            }
        }
        return Set.copyOf(types);
    }

    private static ContractType typeOf(String name) {
        var words = new java.util.HashSet<String>();
        for (var word : name.split("[ -]")) {
            words.add(word);
            if (word.length() > 3 && word.endsWith("s")) {
                words.add(word.substring(0, word.length() - 1)); // "NDAs", "SOWs"
            }
        }
        if (name.contains("data processing") || words.contains("dpa")) {
            return ContractType.DPA;
        }
        if (name.contains("statement of work") || words.contains("sow")) {
            return ContractType.SOW;
        }
        if (name.contains("saas") || name.contains("subscription") || name.contains("order form")) {
            return ContractType.SAAS;
        }
        if (name.contains("licen")) {
            return ContractType.SWL;
        }
        if (words.contains("nda") || words.contains("mnda") || name.contains("non-disclosure")
                || name.contains("nondisclosure") || name.contains("non disclosure")
                || name.contains("confidentiality agreement")) {
            if (name.contains("mutual") || words.contains("mnda")) {
                return ContractType.MNDA;
            }
            if (name.contains("one-way") || name.contains("one way") || name.contains("unilateral")) {
                return ContractType.ONDA;
            }
            return ContractType.NDA;
        }
        if (words.contains("msa") || name.contains("master services") || name.contains("master agreement")) {
            return ContractType.MSA;
        }
        return ContractType.OTHER;
    }
}
