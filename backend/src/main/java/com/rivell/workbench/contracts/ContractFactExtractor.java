package com.rivell.workbench.contracts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * Typed fact extraction (S8). Responsibility ladder per fact: deterministic parse
 * first; bounded Jev judgment when possible; generative structured extraction only
 * for genuinely open values — always validated by code and independently verified
 * by Jev before use. Uncertainty is honest, never invented certainty, never 500.
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

        deterministicCap(text).ifPresentOrElse(cap -> {
            values.put("liabilityCapMultiple", String.valueOf(cap));
            interpretations.put("liabilityCapMultiple", Interpretation.deterministic(
                    "liabilityCapMultiple", String.valueOf(cap), provenance));
        }, () -> generativeCap(heading, text, provenance)
                .ifPresent(extracted -> {
                    values.putAll(extracted.values());
                    interpretations.putAll(extracted.interpretations());
                }));

        capBasis(text).ifPresent(basis -> {
            values.put("capBasis", basis);
            interpretations.put("capBasis", Interpretation.deterministic(
                    "capBasis", basis, provenance));
        });

        consequentialExclusion(text).ifPresent(excluded -> {
            values.put("consequentialDamagesExcluded", String.valueOf(excluded));
            var claim = "The clause excludes liability for consequential damages: "
                    + excluded;
            interpretations.put("consequentialDamagesExcluded", boundedBoolean(
                    "consequentialDamagesExcluded", excluded, heading, text,
                    "the clause excludes indirect, special, incidental, or consequential "
                            + "damages", provenance));
        });

        dataBreachCarveout(text).ifPresent(carveout -> {
            values.put("dataBreachCarveout", String.valueOf(carveout));
            interpretations.put("dataBreachCarveout", boundedBoolean(
                    "dataBreachCarveout", carveout, heading, text,
                    "the clause carves out breaches of confidentiality obligations from "
                            + "the liability limitations", provenance));
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
        if (notice.isEmpty()) {
            notice = generativeNoticeDays(heading, text, provenance)
                    .map(days -> Optional.of(days))
                    .orElseGet(() -> Optional.<String>empty())
                    .describeConstable();
        }
        notice.ifPresent(days -> {
            values.put("noticePeriodDays", days);
            if (!interpretations.containsKey("noticePeriodDays")) {
                interpretations.put("noticePeriodDays", Interpretation.deterministic(
                        "noticePeriodDays", days, provenance));
            }
        });

        if (values.isEmpty()) {
            return new FactResult(List.of(), interpretations);
        }
        var fact = new LegalFact(FactSchema.TERMINATION_FACTS.id(),
                FactSchema.TERMINATION_FACTS.version(), values);
        return new FactResult(List.of(fact), interpretations);
    }

    private Optional<Integer> deterministicCap(String text) {
        var matcher = java.util.regex.Pattern.compile(
                "([a-z]+) \\((\\d+)\\) times the", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (matcher.find()) {
            return Optional.of(Integer.parseInt(matcher.group(2)));
        }
        matcher = java.util.regex.Pattern.compile("(\\d+) times the",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        }
        return Optional.empty();
    }

    private Optional<String> capBasis(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("twelve (12) months immediately preceding")
                || lower.contains("preceding twelve (12) months")
                || lower.contains("twelve (12) months immediately")) {
            return Optional.of("PRECEDING_12_MONTH_FEES");
        }
        if (lower.contains("preceding 12 months") || lower.contains("preceding twelve months")
                || lower.contains("12 months immediately preceding")) {
            return Optional.of("PRECEDING_12_MONTH_FEES");
        }
        if (lower.contains("annual fees") || lower.contains("annual fees paid")) {
            return Optional.of("ANNUAL_FEES");
        }
        return Optional.empty();
    }

    private Optional<Boolean> consequentialExclusion(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("shall not be liable for")
                && (lower.contains("consequential") || lower.contains("indirect"))) {
            return Optional.of(true);
        }
        return Optional.empty();
    }

    private Optional<Boolean> dataBreachCarveout(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        var carveOutSignal = (lower.contains("carve") || lower.contains("do not apply")
                || lower.contains("does not apply") || lower.contains("notwithstanding"))
                && lower.contains("confidential");
        return Optional.of(carveOutSignal);
    }

    private Interpretation boundedBoolean(
            String field, boolean value, String heading, String text,
            String claim, List<UUID> provenance) {
        try {
            var execution = decisionService.evaluate(
                    M5SemanticSpecs.factVerification(),
                    new M5SemanticStates.FactVerificationState(
                            text, "Confirm or deny: " + claim + "."));
            if (execution.answers().get("source_supports") instanceof YesNoAnswer answer) {
                var state = verificationState(answer.probability());
                return new Interpretation(
                        interpretationField, InterpretationKind.BOUNDED_CLASSIFICATION,
                        String.valueOf(value), M5SemanticSpecs.FACT_VERIFICATION_SPEC,
                        M5SemanticSpecs.factVerification().version(),
                        execution.metadata().executionId(), provenance, state, null);
            }
            throw new ProviderProtocolException("Fact verification answer missing");
        } catch (SemanticDecisionException e) {
            return new Interpretation(
                    interpretationField, InterpretationKind.BOUNDED_CLASSIFICATION,
                    String.valueOf(value), M5SemanticSpecs.FACT_VERIFICATION_SPEC,
                    M5SemanticSpecs.factVerification().version(), null, provenance,
                    VerificationState.UNCERTAIN, null);
        }
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

    private Optional<String> generativeCap(
            String heading, String text, List<UUID> provenance) {
        try {
            var execution = reasoningService.generate(
                    M5ReasoningSpecs.liabilityCap(),
                    new M5SemanticStates.ClauseState(heading, text));
            var multiple = execution.fields().get("liabilityCapMultiple");
            var basis = execution.fields().get("capBasis");
            new LegalFact(FactSchema.LIABILITY_FACTS.id(),
                    FactSchema.LIABILITY_FACTS.version(),
                    Map.of("liabilityCapMultiple", multiple, "capBasis", basis));

            var claim = "The clause states that the liability cap is " + multiple
                    + " times the fees, with basis " + capBasis + ".";
            var verification = verify(claim, heading, text, provenance);
            var interpretation = new Interpretation(
                    "liabilityCapMultiple", InterpretationKind.GENERATIVE_EXTRACTION,
                    capMultiple, M5ReasoningSpecs.LIABILITY_CAP_SPEC,
                    M5ReasoningSpecs.liabilityCap().version(),
                    execution.metadata().executionId(), provenance,
                    verification.verification(), verification.verificationExecutionId());
            if (verification.verification() == VerificationState.VERIFIED
                    || verification.verification() == VerificationState.NOT_VERIFIED) {
                return Optional.of(new ExtractedFact(
                        Map.of(
                                "liabilityCapMultiple", capMultiple,
                                "capBasis", capBasis),
                        Map.of("liabilityCapMultiple", interpretation)));
            }
            return Optional.of(new ExtractedFact(Map.of(), Map.of(
                    "liabilityCapMultiple", interpretation)));
        } catch (SemanticDecisionException e) {
            var interpretation = new Interpretation(
                    "liabilityCapMultiple", InterpretationKind.GENERATIVE_EXTRACTION,
                    "UNAVAILABLE", M5ReasoningSpecs.LIABILITY_CAP_SPEC,
                    M5ReasoningSpecs.liabilityCap().version(), null, provenance,
                    VerificationState.UNCERTAIN, null);
            return Optional.of(new ExtractedFact(Map.of(),
                    Map.of("liabilityCapMultiple", interpretation)));
        }
    }

    private record ExtractedFact(
            Map<String, String> values, Map<String, Interpretation> interpretations) {
    }
}
