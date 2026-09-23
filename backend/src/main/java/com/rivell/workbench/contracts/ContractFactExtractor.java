package com.rivell.workbench.contracts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;

/**
 * Typed fact extraction (S8). Responsibility ladder per fact: deterministic parse
 * first; generative structured extraction only for genuinely open values — always
 * validated by code and independently verified by Jev before the value is used.
 * Uncertainty is honest; semantic uncertainty is never a technical failure.
 */
public final class ContractFactExtractor {

    private final SemanticDecisionService decisionService;
    private final GenerativeReasoningService reasoningService;
    private final ContractEnrichmentProperties properties;

    public ContractFactExtractor(
            SemanticDecisionService decisionService,
            GenerativeReasoningService reasoningService,
            ContractEnrichmentProperties properties) {
        this.decisionService = decisionService;
        this.reasoningService = reasoningService;
        this.properties = properties;
    }

    public record FactResult(List<LegalFact> facts, Map<String, Interpretation> interpretations) {
    }

    public FactResult extract(LegalConcept concept, String heading, String text,
            List<UUID> provenance) {
        return switch (concept) {
            case LIMITATION_OF_LIABILITY -> liabilityFacts(heading, text, provenance);
            case TERM_TERMINATION -> terminationFacts(heading, text, provenance);
            default -> new FactResult(List.of(), Map.of());
        };
    }

    private FactResult liabilityFacts(String heading, String text, List<UUID> provenance) {
        var values = new LinkedHashMap<String, String>();
        var interpretations = new LinkedHashMap<String, Interpretation>();

        var cap = deterministicCap(text);
        if (cap.isPresent()) {
            values.put("liabilityCapMultiple", cap.get());
            interpretations.put("liabilityCapMultiple", Interpretation.deterministic(
                    "liabilityCapMultiple", cap.get(), provenance));
        } else {
            var extracted = generativeCap(heading, text, provenance);
            extracted.interpretations().forEach(interpretations::putIfAbsent);
            if (verificationUsable(extracted)) {
                values.putAll(extracted.values());
            }
        }

        capBasis(text).ifPresent(basis -> {
            if (!values.containsKey("capBasis")) {
                values.put("capBasis", basis);
            }
            interpretations.putIfAbsent("capBasis", Interpretation.deterministic(
                    "capBasis", basis, provenance));
        });

        consequentialExclusion(text).ifPresent(excluded -> {
            values.put("consequentialDamagesExcluded", excluded);
            interpretations.put("consequentialDamagesExcluded", verifiedCue(
                    "consequentialDamagesExcluded", excluded, heading, text,
                    "The clause excludes liability for indirect, special, incidental, or "
                            + "consequential damages.",
                    provenance));
        });

        dataBreachCarveout(text).ifPresent(carveout -> {
            if (values.containsKey("liabilityCapMultiple")
                    || values.containsKey("consequentialDamagesExcluded")) {
                values.put("dataBreachCarveout", carveout);
                interpretations.put("dataBreachCarveout", verifiedCue(
                        "dataBreachCarveout", carveout, heading, text,
                        "The clause carves out breaches of confidentiality obligations from "
                                + "the liability limitations.",
                        provenance));
            }
        });

        if (values.isEmpty()) {
            return new FactResult(List.of(), interpretations);
        }
        var fact = new LegalFact(FactSchema.LIABILITY_FACTS.id(),
                FactSchema.LIABILITY_FACTS.version(), values);
        return new FactResult(List.of(fact), interpretations);
    }

    private FactResult terminationFacts(String heading, String text, List<UUID> provenance) {
        var values = new LinkedHashMap<String, String>();
        var interpretations = new LinkedHashMap<String, Interpretation>();

        var notice = deterministicNoticeDays(text);
        if (notice.isEmpty() && !hasConvenienceLanguage(text)) {
            return new FactResult(List.of(), interpretations);
        }
        if (notice.isPresent()) {
            values.put("noticePeriodDays", notice.get());
            interpretations.put("noticePeriodDays", Interpretation.deterministic(
                    "noticePeriodDays", notice.get(), provenance));
        } else {
            var extracted = generativeNoticeDays(heading, text, provenance);
            extracted.interpretations().forEach(interpretations::putIfAbsent);
            if (verificationUsable(extracted)) {
                values.putAll(extracted.values());
            }
        }

        if (values.isEmpty()) {
            return new FactResult(List.of(), interpretations);
        }
        var fact = new LegalFact(FactSchema.TERMINATION_FACTS.id(),
                FactSchema.TERMINATION_FACTS.version(), values);
        return new FactResult(List.of(fact), interpretations);
    }

    private boolean hasConvenienceLanguage(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("for convenience");
    }

    private Optional<String> deterministicCap(String text) {
        var matcher = Pattern.compile("([a-z]+) \\((\\d+)\\) times the",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(2));
        }
        matcher = Pattern.compile("(\\d+) times the", Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<String> capBasis(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("months immediately preceding")
                || lower.contains("preceding twelve (12) months")
                || lower.contains("preceding 12 months")) {
            return Optional.of("PRECEDING_12_MONTH_FEES");
        }
        if (lower.contains("annual fees")) {
            return Optional.of("ANNUAL_FEES");
        }
        return Optional.empty();
    }

    private Optional<String> consequentialExclusion(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        var excludes = lower.contains("shall not be liable for")
                || lower.contains("neither party shall be liable")
                || lower.contains("will not be liable for")
                || lower.contains("not be responsible or liable");
        if (excludes && (lower.contains("consequential") || lower.contains("indirect"))) {
            return Optional.of("true");
        }
        return Optional.empty();
    }

    private Optional<String> dataBreachCarveout(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        var carveOutSignal = (lower.contains("carve") || lower.contains("do not apply")
                || lower.contains("does not apply") || lower.contains("notwithstanding"))
                && lower.contains("confidential");
        return Optional.of(String.valueOf(carveOutSignal));
    }

    private Optional<String> deterministicNoticeDays(String text) {
        var matcher = Pattern.compile(
                "(?:upon|on) ([a-z]+) \\((\\d+)\\) days['’]?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(2));
        }
        matcher = Pattern.compile("(?:upon|on) (\\d+) days['’]?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Generative extraction path for the liability cap: strict typed output, code
     * validation via the fact schema, then independent Jev verification. The value
     * is used only when verification confirms it; otherwise the interpretation
     * records the proposal honestly (UNCERTAIN/DISPUTED) without an effective value.
     */
    private Extracted generativeCap(String heading, String text, List<UUID> provenance) {
        try {
            var execution = reasoningService.generate(
                    M5ReasoningSpecs.liabilityCap(),
                    new M5SemanticStates.ClauseState(heading, text));
            var multiple = execution.fields().get("liabilityCapMultiple");
            var basis = execution.fields().get("capBasis");
            new LegalFact(FactSchema.LIABILITY_FACTS.id(),
                    FactSchema.LIABILITY_FACTS.version(),
                    Map.of("liabilityCapMultiple", multiple, "capBasis", basis));

            var verification = verify(
                    "The clause states that the liability cap is " + multiple
                            + " times the fees, with the basis " + basis + ".",
                    heading, text, provenance);
            var interpretation = new Interpretation(
                    "liabilityCapMultiple", InterpretationKind.GENERATIVE_EXTRACTION,
                    multiple, M5ReasoningSpecs.LIABILITY_CAP_SPEC,
                    M5ReasoningSpecs.liabilityCap().version(),
                    execution.metadata().executionId(), provenance,
                    verification.verification(), verification.verificationExecutionId());
            var values = verification.verification() == VerificationState.VERIFIED
                    ? new LinkedHashMap<String, String>(Map.of(
                            "liabilityCapMultiple", multiple,
                            "capBasis", basis))
                    : Map.<String, String>of();
            return new Extracted(values,
                    Map.of("liabilityCapMultiple", interpretation));
        } catch (SemanticDecisionException e) {
            var interpretation = new Interpretation(
                    "liabilityCapMultiple", InterpretationKind.GENERATIVE_EXTRACTION,
                    "UNAVAILABLE", M5ReasoningSpecs.LIABILITY_CAP_SPEC,
                    M5ReasoningSpecs.liabilityCap().version(), null, provenance,
                    VerificationState.UNCERTAIN, null);
            return new Extracted(Map.of(), Map.of("liabilityCapMultiple", interpretation));
        }
    }

    private Extracted generativeNoticeDays(String heading, String text, List<UUID> provenance) {
        try {
            var execution = reasoningService.generate(
                    M5ReasoningSpecs.terminationNotice(),
                    new M5SemanticStates.ClauseState(heading, text));
            var days = execution.fields().get("noticePeriodDays");
            new LegalFact(FactSchema.TERMINATION_FACTS.id(),
                    FactSchema.TERMINATION_FACTS.version(),
                    Map.of("noticePeriodDays", days));

            var verification = verify(
                    "The clause states a termination-for-convenience notice period of "
                            + days + " days.",
                    heading, text, provenance);
            var interpretation = new Interpretation(
                    "noticePeriodDays", InterpretationKind.GENERATIVE_EXTRACTION,
                    days, M5ReasoningSpecs.TERMINATION_NOTICE_SPEC,
                    M5ReasoningSpecs.terminationNotice().version(),
                    execution.metadata().executionId(), provenance,
                    verification.verification(), verification.verificationExecutionId());
            var values = verification.verification() == VerificationState.VERIFIED
                    ? new LinkedHashMap<String, String>(Map.of("noticePeriodDays", days))
                    : Map.<String, String>of();
            return new Extracted(values, Map.of("noticePeriodDays", interpretation));
        } catch (SemanticDecisionException e) {
            var interpretation = new Interpretation(
                    "noticePeriodDays", InterpretationKind.GENERATIVE_EXTRACTION,
                    "UNAVAILABLE", M5ReasoningSpecs.TERMINATION_NOTICE_SPEC,
                    M5ReasoningSpecs.terminationNotice().version(), null, provenance,
                    VerificationState.UNCERTAIN, null);
            return new Extracted(Map.of(), Map.of("noticePeriodDays", interpretation));
        }
    }

    private record Extracted(
            Map<String, String> values, Map<String, Interpretation> interpretations) {

        boolean usable() {
            return !values.isEmpty();
        }
    }

    private boolean verificationUsable(Extracted extracted) {
        return extracted.usable();
    }

    private Interpretation verifiedCue(
            String field, String value, String heading, String text, String claim,
            List<UUID> provenance) {
        try {
            var execution = decisionService.evaluate(
                    M5SemanticSpecs.factVerification(),
                    new M5SemanticStates.FactVerificationState(text, claim));
            if (!(execution.answers().get("source_supports")
                    instanceof com.rivell.workbench.intelligence.YesNoAnswer answer)) {
                throw new ProviderProtocolException("Fact verification answer missing");
            }
            return new Interpretation(
                    field, InterpretationKind.BOUNDED_CLASSIFICATION, value,
                    M5SemanticSpecs.FACT_VERIFICATION_SPEC,
                    M5SemanticSpecs.factVerification().version(),
                    execution.metadata().executionId(), provenance,
                    verificationState(answer.probability()), null);
        } catch (SemanticDecisionException e) {
            return new Interpretation(
                    field, InterpretationKind.BOUNDED_CLASSIFICATION, value,
                    M5SemanticSpecs.FACT_VERIFICATION_SPEC,
                    M5SemanticSpecs.factVerification().version(), null, provenance,
                    VerificationState.UNCERTAIN, null);
        }
    }

    private Verification verify(
            String claim, String heading, String text, List<UUID> provenance) {
        try {
            var execution = decisionService.evaluate(
                    M5SemanticSpecs.factVerification(),
                    new M5SemanticStates.FactVerificationState(text, claim));
            if (execution.answers().get("source_supports")
                    instanceof com.rivell.workbench.intelligence.YesNoAnswer answer) {
                return new Verification(
                        verificationState(answer.probability()),
                        execution.metadata().executionId());
            }
            throw new ProviderProtocolException("Verification answer missing");
        } catch (SemanticDecisionException e) {
            return new Verification(VerificationState.UNCERTAIN, null);
        }
    }

    private record Verification(VerificationState verification, UUID verificationExecutionId) {
    }

    private VerificationState verificationState(double probability) {
        if (probability >= properties.verificationThreshold()) {
            return VerificationState.VERIFIED;
        }
        if (probability < properties.disputedThreshold()) {
            return VerificationState.DISPUTED;
        }
        return VerificationState.UNCERTAIN;
    }
}
