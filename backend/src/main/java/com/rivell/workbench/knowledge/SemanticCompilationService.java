package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * The M4 semantic compilation pipeline: attaches machine interpretation to deterministic
 * candidates. Stages: artifact-type assessment; per-candidate concept normalization (M3
 * spec), artifact-specific bounded strategy, source-scope narrowing; conflict analysis
 * via the detector port. Model calls happen with no open transaction. Only technical
 * provider failure fails the artifact — semantic uncertainty flows to review instead.
 */
public class SemanticCompilationService {

    private final SemanticDecisionService decisionService;
    private final SemanticPolicyProperties properties;
    private final ConceptClassificationProperties conceptProperties;
    private final DuplicateConflictDetector conflictDetector;
    private final GenerativeFieldExtractor generativeFieldExtractor;
    private final CandidateReviewCategorizer categorizer;

    public SemanticCompilationService(
            SemanticDecisionService decisionService,
            SemanticPolicyProperties properties,
            ConceptClassificationProperties conceptProperties,
            DuplicateConflictDetector conflictDetector,
            GenerativeFieldExtractor generativeFieldExtractor,
            CandidateReviewCategorizer categorizer) {
        this.decisionService = decisionService;
        this.properties = properties;
        this.conceptProperties = conceptProperties;
        this.conflictDetector = conflictDetector;
        this.generativeFieldExtractor = generativeFieldExtractor;
        this.categorizer = categorizer;
    }

    public record SemanticCompilation(
            boolean semanticAvailable,
            ArtifactTypeAssessment assessment,
            List<KnowledgeCandidate> candidates) {
    }

    public SemanticCompilation compile(
            KnowledgeArtifact artifact, List<ExtractionUnit> units,
            List<KnowledgeCandidate> candidates, List<KnowledgeCandidate> publishedCandidates) {
        var unitsById = units.stream()
                .collect(Collectors.toMap(ExtractionUnit::id, Function.identity()));

        ArtifactTypeAssessment assessment;
        try {
            assessment = new ArtifactTypeAssessor(decisionService, properties)
                    .assess(artifact, sampleTexts(units));
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            assessment = null;
        }

        var semanticAvailable = assessment != null;
        var compiled = new ArrayList<KnowledgeCandidate>(candidates.size());
        for (var candidate : candidates) {
            var unit = unitsById.get(candidate.extractionUnitId());
            KnowledgeCandidate enriched;
            try {
                enriched = interpret(candidate, artifact, unit);
            } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
                semanticAvailable = false;
                enriched = candidate;
            }
            var signals = conflictDetector.inspect(enriched, publishedCandidates);
            var withSignals = enriched.withConflictSignals(signals);
            var categorization = categorizer.categorize(withSignals, assessment);
            compiled.add(withSignals.withReviewOutcome(
                    categorization.category(), categorization.reasons()));
        }
        return new SemanticCompilation(semanticAvailable, assessment, compiled);
    }

    private KnowledgeCandidate interpret(
            KnowledgeCandidate candidate, KnowledgeArtifact artifact, ExtractionUnit unit) {
        var state = new M4SemanticStates.PlaybookUnitState(
                unit.contentText(), unit.title(), unit.kind(), candidate.payload().columns());
        var concepts = classifyConcepts(state);
        var fields = new LinkedHashMap<String, FieldInterpretation>();
        var builder = new SemanticFieldsBuilder(concepts.probabilities());

        switch (artifact.artifactType()) {
            case PLAYBOOK -> interpretPlaybook(state, builder, fields);
            case CLAUSE_LIBRARY -> interpretClauseVariant(state, builder, fields);
            case TEMPLATE, EXECUTED_PRECEDENT, NEGOTIATION_HISTORY, EXTERNAL_REFERENCE -> { }
        }
        interpretScopeNarrowing(state, fields);

        var semantic = builder.build();
        var conceptKey = SemanticFields.primaryConcept(
                concepts.probabilities(), conceptProperties.conceptThreshold())
                .map(Enum::name).orElse(null);
        return candidate.withSemanticOutcome(semantic, List.copyOf(fields.values()), conceptKey);
    }

    private ConceptOutcome classifyConcepts(M4SemanticStates.PlaybookUnitState state) {
        var execution = decisionService.evaluate(ExtractionUnitConceptSpec.spec(), state);
        var probabilities = new LinkedHashMap<LegalConcept, Double>();
        for (var concept : LegalConcept.values()) {
            var answer = execution.answers().get(ExtractionUnitConceptSpec.questionName(concept));
            if (answer instanceof YesNoAnswer yesNo) {
                probabilities.put(concept, yesNo.probability());
            }
        }
        return new ConceptOutcome(execution, probabilities);
    }

    private void interpretPlaybook(
            M4SemanticStates.PlaybookUnitState state,
            SemanticFieldsBuilder builder,
            Map<String, FieldInterpretation> fields) {
        var execution = decisionService.evaluate(M4SemanticSpecs.playbookPositions(), state);
        var positions = new LinkedHashSet<PositionRole>();
        for (var role : M4SemanticSpecs.playbookRoles()) {
            var answer = execution.answers().get(M4SemanticSpecs.positionQuestionName(role));
            if (answer instanceof YesNoAnswer yesNo && yesNo.probability() >= properties.positionProposal()) {
                positions.add(role);
            }
        }
        var approval = execution.answers().get("requires_approval");
        var requiresApproval = approval instanceof YesNoAnswer yesNo
                ? yesNo.probability() >= properties.approvalProposal()
                : null;
        builder.positions(positions).requiresApproval(requiresApproval);
        if (positions.contains(PositionRole.ESCALATION)) {
            interpretEscalationTrigger(state, stateColumnsHasEscalation(state), builder, fields);
        }
        if (!positions.isEmpty()) {
            fields.put("positions", new FieldInterpretation(
                    "positions", InterpretationKind.BOUNDED_CLASSIFICATION,
                    positions.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElseThrow(),
                    M4SemanticSpecs.PLAYBOOK_POSITION_SPEC, M4SemanticSpecs.playbookPositions().version(),
                    execution.metadata().executionId(), List.of(), VerificationState.NOT_VERIFIED, null));
        }
        if (requiresApproval != null) {
            fields.put("requiresApproval", new FieldInterpretation(
                    "requiresApproval", InterpretationKind.BOUNDED_CLASSIFICATION,
                    String.valueOf(requiresApproval),
                    M4SemanticSpecs.PLAYBOOK_APPROVAL_SPEC, M4SemanticSpecs.playbookPositions().version(),
                    execution.metadata().executionId(), List.of(), VerificationState.NOT_VERIFIED, null));
        }
    }

    /**
     * Deterministic first: verbatim table cell values already carry the trigger. Only
     * free prose without a usable column value goes through generative extraction, and
     * the extracted value is independently verified before becoming a candidate field.
     */
    private void interpretEscalationTrigger(
            M4SemanticStates.PlaybookUnitState state,
            boolean deterministicValueAvailable,
            SemanticFieldsBuilder builder,
            Map<String, FieldInterpretation> fields) {
        var verbatim = deterministicValueAvailable
                ? state.columns().entrySet().stream()
                        .filter(entry -> entry.getValue() != null
                                && entry.getValue().toLowerCase(java.util.Locale.ROOT).contains("escalat"))
                        .map(Map.Entry::getValue)
                        .findFirst()
                : java.util.Optional.<String>empty();
        if (verbatim.isPresent()) {
            var value = verbatim.get();
            builder.escalationTrigger(value);
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.DETERMINISTIC, value,
                    "deterministic-column-extraction", 1,
                    java.util.UUID.randomUUID(), List.of(), VerificationState.VERIFIED, null));
            return;
        }
        var extracted = generativeFieldExtractor.extractEscalationTrigger(state);
        if (extracted.isPresent()) {
            var verified = extracted.get();
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.GENERATIVE_EXTRACTION, verified.value(),
                    M4ReasoningSpecs.ESCALATION_TRIGGER_SPEC, M4ReasoningSpecs.escalationTrigger().version(),
                    verified.generationExecutionId(), List.of(), verified.verification(),
                    verified.verificationExecutionId()));
            if (verified.verification() == VerificationState.VERIFIED) {
                builder.escalationTrigger(verified.value());
            }
        }
    }

    private boolean stateColumnsHasEscalation(M4SemanticStates.PlaybookUnitState state) {
        return state.columns().values().stream()
                .anyMatch(value -> value != null
                        && value.toLowerCase(java.util.Locale.ROOT).contains("escalat"));
    }

    private void interpretClauseVariant(
            M4SemanticStates.PlaybookUnitState state,
            SemanticFieldsBuilder builder,
            Map<String, FieldInterpretation> fields) {
        var execution = decisionService.evaluate(M4SemanticSpecs.clauseVariantRole(), state);
        var answer = (ChoiceAnswer) execution.answers().get("variant_role");
        var selected = PositionRole.valueOf(answer.selected());
        var probability = answer.probabilities().get(answer.selected());
        if (probability >= properties.variantRoleProposal()) {
            builder.variantRole(selected);
            fields.put("variantRole", new FieldInterpretation(
                    "variantRole", InterpretationKind.BOUNDED_CLASSIFICATION, selected.name(),
                    M4SemanticSpecs.CLAUSE_VARIANT_ROLE_SPEC, M4SemanticSpecs.clauseVariantRole().version(),
                    execution.metadata().executionId(), List.of(), VerificationState.NOT_VERIFIED, null));
        }
    }

    private void interpretScopeNarrowing(
            M4SemanticStates.PlaybookUnitState state,
            Map<String, FieldInterpretation> fields) {
        var execution = decisionService.evaluate(M4SemanticSpecs.scopeNarrowing(), state);
        var narrows = execution.answers().get("narrows_scope");
        var jurisdiction = execution.answers().get("jurisdiction");
        if (narrows instanceof YesNoAnswer yesNo && yesNo.probability() >= properties.scopeNarrowProposal()
                && jurisdiction instanceof ChoiceAnswer choice
                && choice.probabilities().get(choice.selected()) >= properties.jurisdictionProposal()
                && !choice.selected().equals(KnownJurisdiction.UNSPECIFIED.name())) {
            fields.put("scope", new FieldInterpretation(
                    "scope", InterpretationKind.BOUNDED_CLASSIFICATION, choice.selected(),
                    M4SemanticSpecs.SCOPE_NARROWING_SPEC, M4SemanticSpecs.scopeNarrowing().version(),
                    execution.metadata().executionId(), List.of(), VerificationState.NOT_VERIFIED, null));
        }
    }

    private static List<String> sampleTexts(List<ExtractionUnit> units) {
        return units.stream()
                .map(ExtractionUnit::contentText)
                .filter(text -> text != null && !text.isBlank())
                .limit(3)
                .map(text -> text.length() > 800 ? text.substring(0, 800) : text)
                .toList();
    }

    private record ConceptOutcome(
            DecisionExecution execution,
            Map<LegalConcept, Double> probabilities) {
    }

    private static final class SemanticFieldsBuilder {
        private Set<PositionRole> positions = Set.of();
        private Boolean requiresApproval;
        private String escalationTrigger;
        private PositionRole variantRole;
        private Map<LegalConcept, Double> conceptProbabilities;

        SemanticFieldsBuilder(Map<LegalConcept, Double> conceptProbabilities) {
            this.conceptProbabilities = conceptProbabilities;
        }

        SemanticFieldsBuilder positions(Set<PositionRole> value) {
            this.positions = value;
            return this;
        }

        SemanticFieldsBuilder requiresApproval(Boolean value) {
            this.requiresApproval = value;
            return this;
        }

        SemanticFieldsBuilder variantRole(PositionRole value) {
            this.variantRole = value;
            return this;
        }

        SemanticFieldsBuilder escalationTrigger(String value) {
            this.escalationTrigger = value;
            return this;
        }

        SemanticFields build() {
            return new SemanticFields(
                    positions, requiresApproval, escalationTrigger, variantRole, conceptProbabilities);
        }
    }
}
