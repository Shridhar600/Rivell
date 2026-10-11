package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.BoundedParallel;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.TopicClassifier;

/**
 * The M4 semantic compilation pipeline: attaches machine interpretation to deterministic
 * candidates. Stages: artifact-type assessment; per-artifact unknown-label resolution;
 * one record-kind decision per record (records that are not positions or approved wording
 * stay units, never candidates); per-candidate interpretation; conflict analysis via the
 * detector port. Model calls happen with no open transaction. Provider failure never
 * drops knowledge: the record stays a candidate with SEMANTIC_ANALYSIS_UNAVAILABLE.
 */
public class SemanticCompilationService {

    private static final String DETERMINISTIC_COLUMN_SPEC = "deterministic-column-extraction";

    private final SemanticDecisionService decisionService;
    private final SemanticPolicyProperties properties;
    private final DuplicateConflictDetector conflictDetector;
    private final GenerativeFieldExtractor generativeFieldExtractor;
    private final CandidateReviewCategorizer categorizer;
    private final TopicClassifier topicClassifier;
    private final KnowledgeRecordSemantics recordSemantics;

    public SemanticCompilationService(
            SemanticDecisionService decisionService,
            SemanticPolicyProperties properties,
            DuplicateConflictDetector conflictDetector,
            GenerativeFieldExtractor generativeFieldExtractor,
            CandidateReviewCategorizer categorizer,
            TopicClassifier topicClassifier) {
        this.decisionService = decisionService;
        this.properties = properties;
        this.conflictDetector = conflictDetector;
        this.generativeFieldExtractor = generativeFieldExtractor;
        this.categorizer = categorizer;
        this.topicClassifier = topicClassifier;
        this.recordSemantics = new KnowledgeRecordSemantics(decisionService);
    }

    public record SemanticCompilation(
            boolean semanticAvailable,
            ArtifactTypeAssessment assessment,
            List<KnowledgeCandidate> candidates,
            List<ExtractionUnit> units) {
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
        } catch (SemanticDecisionException e) {
            assessment = null;
        }

        var semanticAvailable = assessment != null;
        var labelRoles = recordSemantics.resolveUnknownLabels(artifact, candidates);
        var recordKinds = new LinkedHashMap<UUID, KnowledgeRecordKind>();
        var kindUnavailable = new LinkedHashSet<UUID>();
        var eligible = new ArrayList<KnowledgeCandidate>(candidates.size());
        var rolesByCandidate = new LinkedHashMap<UUID, Map<String, RecordFieldRole>>();
        for (var candidate : candidates) {
            rolesByCandidate.put(candidate.id(), fieldRoles(candidate, labelRoles));
        }
        // record decisions are independent per record: run them concurrently, keep source order
        var kinds = BoundedParallel.map(candidates, properties.compileConcurrency(),
                candidate -> recordSemantics.decideKind(artifact,
                        unitsById.get(candidate.extractionUnitId()), candidate,
                        rolesByCandidate.get(candidate.id())));
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var unit = unitsById.get(candidate.extractionUnitId());
            var kind = kindOf(kinds.get(index), unit);
            if (kind.isEmpty()) {
                semanticAvailable = false;
                kindUnavailable.add(unit.id());
                eligible.add(candidate);
                continue;
            }
            recordKinds.put(unit.id(), kind.get());
            if (eligibleForCandidates(artifact.artifactType(), kind.get())) {
                eligible.add(candidate);
            }
        }

        var interpretable = Math.min(eligible.size(), properties.maxInterpretedCandidates());
        var interpreted = BoundedParallel.map(eligible.subList(0, interpretable),
                properties.compileConcurrency(),
                candidate -> interpret(candidate, artifact,
                        unitsById.get(candidate.extractionUnitId()),
                        rolesByCandidate.getOrDefault(candidate.id(), Map.of())));
        if (interpretable < eligible.size()) {
            semanticAvailable = false;
        }
        var compiled = new ArrayList<KnowledgeCandidate>(eligible.size());
        for (int index = 0; index < eligible.size(); index++) {
            var candidate = eligible.get(index);
            var unit = unitsById.get(candidate.extractionUnitId());
            var enriched = candidate;
            if (index < interpretable) {
                var outcome = interpreted.get(index);
                if (outcome.failure() == null) {
                    enriched = outcome.value();
                } else if (outcome.failure() instanceof SemanticDecisionException e) {
                    LoggerFactory.getLogger(SemanticCompilationService.class)
                            .warn("Semantic interpretation degraded for candidate {} ({} {})",
                            candidate.id(), e.getClass().getSimpleName(), e.getMessage());
                    semanticAvailable = false;
                } else {
                    throw outcome.failure();
                }
            }
            var roles = rolesByCandidate.getOrDefault(candidate.id(), Map.<String, RecordFieldRole>of());
            enriched = withSourceApplicability(withResolvedFieldRoles(enriched, roles), unit, roles);
            var signals = conflictDetector.inspect(enriched, publishedCandidates);
            var withSignals = enriched.withConflictSignals(signals);
            var categorization = categorizer.categorize(withSignals, assessment);
            var reasons = categorization.reasons();
            var category = categorization.category();
            if (kindUnavailable.contains(unit.id())) {
                var merged = new LinkedHashSet<>(reasons);
                merged.add(ReviewReason.SEMANTIC_ANALYSIS_UNAVAILABLE);
                reasons = Set.copyOf(merged);
                if (category == ReviewCategory.ROUTINE) {
                    category = ReviewCategory.ELEVATED;
                }
            }
            compiled.add(withSignals.withReviewOutcome(category, reasons));
        }

        var compiledUnits = units.stream()
                .map(unit -> recordKinds.containsKey(unit.id())
                        ? unit.withRecordKind(recordKinds.get(unit.id()))
                        : unit)
                .toList();
        return new SemanticCompilation(semanticAvailable, assessment, List.copyOf(compiled),
                compiledUnits);
    }

    private static Optional<KnowledgeRecordKind> kindOf(
            BoundedParallel.Outcome<Optional<KnowledgeRecordKind>> outcome, ExtractionUnit unit) {
        if (outcome.failure() == null) {
            return outcome.value();
        }
        if (outcome.failure() instanceof SemanticDecisionException e) {
            LoggerFactory.getLogger(SemanticCompilationService.class)
                    .warn("Record kind unavailable for unit {} ({} {})",
                            unit.id(), e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
        throw outcome.failure();
    }

    private static boolean eligibleForCandidates(
            ArtifactType artifactType, KnowledgeRecordKind kind) {
        // a playbook rule written as model contract wording ("Kaveri may terminate for
        // material breach…") is still the playbook's preferred position
        return switch (artifactType) {
            case PLAYBOOK, CLAUSE_LIBRARY -> kind == KnowledgeRecordKind.POSITION
                    || kind == KnowledgeRecordKind.WORDING;
            default -> true;
        };
    }

    /**
     * The role of every non-empty field of a cell record. Known labels resolve through the
     * code-owned synonyms; the rest through the per-artifact label decision.
     */
    private static Map<String, RecordFieldRole> fieldRoles(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> unknownLabels) {
        var roles = new LinkedHashMap<String, RecordFieldRole>();
        for (var entry : candidate.payload().columns().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            var role = RecordLabelRoles.known(entry.getKey())
                    .orElseGet(() -> unknownLabels.getOrDefault(
                            RecordLabelRoles.normalize(entry.getKey()), RecordFieldRole.OTHER));
            if (role != RecordFieldRole.OTHER) {
                roles.put(entry.getKey(), role);
            }
        }
        return roles;
    }

    private KnowledgeCandidate interpret(
            KnowledgeCandidate candidate, KnowledgeArtifact artifact, ExtractionUnit unit,
            Map<String, RecordFieldRole> roles) {
        var state = new M4SemanticStates.PlaybookUnitState(
                unit.contentText(), unit.title(), unit.kind(), candidate.payload().columns());
        var topic = topicClassifier.classify(
                topicHeading(candidate, roles), topicText(candidate, roles, unit));
        var fields = new LinkedHashMap<String, FieldInterpretation>();
        var builder = new SemanticFieldsBuilder(topic.probabilities());
        var provenance = unit.sourceNodeIds();

        if (unit.kind() == ExtractionUnitKind.HEADING_SECTION) {
            switch (artifact.artifactType()) {
                case PLAYBOOK -> interpretPlaybook(state, provenance, builder, fields);
                case CLAUSE_LIBRARY -> interpretClauseVariant(state, provenance, builder, fields);
                default -> { }
            }
        } else {
            interpretFieldRoles(candidate, roles, builder, fields, provenance);
        }
        interpretScopeNarrowing(state, provenance, fields);

        var semantic = builder.build();
        var conceptKey = topic.primary() == null ? null : topic.primary().value();
        var topicKeys = new java.util.LinkedHashSet<String>();
        topic.topics().forEach(key -> topicKeys.add(key.value()));
        if (conceptKey != null) {
            topicKeys.add(conceptKey);
        }
        return candidate.withSemanticOutcome(semantic, List.copyOf(fields.values()),
                conceptKey, List.copyOf(topicKeys));
    }

    /**
     * Keeps the roles the field-role decision resolved for labels the code does not know, so
     * review reads "Can live with" as the fallback instead of an unlabelled value.
     */
    private static KnowledgeCandidate withResolvedFieldRoles(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> roles) {
        var resolved = new LinkedHashMap<String, String>();
        roles.forEach((label, role) -> {
            if (RecordLabelRoles.known(label).isEmpty() && role != RecordFieldRole.OTHER) {
                resolved.put(label, role.name());
            }
        });
        return resolved.isEmpty() ? candidate
                : candidate.withMachinePayload(candidate.payload().withFieldRoles(resolved));
    }

    /**
     * The contract types the record itself says it applies to: its APPLICABILITY field, or a
     * prose "Applies to:" line. Deterministic; a record that states nothing keeps the scope it
     * inherited from the manifest.
     */
    private static KnowledgeCandidate withSourceApplicability(
            KnowledgeCandidate candidate, ExtractionUnit unit, Map<String, RecordFieldRole> roles) {
        var stated = Optional.ofNullable(firstFieldValue(candidate, roles, RecordFieldRole.APPLICABILITY));
        if (stated.isEmpty() && unit.kind() == ExtractionUnitKind.HEADING_SECTION) {
            stated = ContractTypeApplicability.statedIn(unit.contentText());
        }
        var types = stated.map(ContractTypeApplicability::contractTypes).orElse(Set.of());
        return types.isEmpty() ? candidate
                : candidate.withScope(candidate.scope().withSourceContractTypes(types));
    }

    /**
     * Topic once per record (design §5 item 4): the record title, or its TOPIC field, is
     * the heading; the PREFERRED or WORDING value (else the full text) is the text.
     */
    private static String topicHeading(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> roles) {
        var topic = firstFieldValue(candidate, roles, RecordFieldRole.TOPIC);
        return topic != null ? topic : candidate.rawLabel();
    }

    private static String topicText(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> roles,
            ExtractionUnit unit) {
        var preferred = firstFieldValue(candidate, roles, RecordFieldRole.PREFERRED);
        if (preferred != null) {
            return preferred;
        }
        var wording = firstFieldValue(candidate, roles, RecordFieldRole.WORDING);
        return wording != null ? wording : unit.contentText();
    }

    private static String firstFieldValue(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> roles,
            RecordFieldRole wanted) {
        for (var key : candidate.payload().orderedKeys()) {
            if (roles.get(key) == wanted) {
                var value = candidate.payload().columns().get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Cell records carry their stance in role-tagged fields; no generative extractor runs
     * for them. Positions, the escalation trigger and the role provenance are deterministic.
     */
    private void interpretFieldRoles(
            KnowledgeCandidate candidate, Map<String, RecordFieldRole> roles,
            SemanticFieldsBuilder builder, Map<String, FieldInterpretation> fields,
            List<UUID> provenance) {
        var positions = new LinkedHashSet<PositionRole>();
        var positionNames = new ArrayList<String>();
        String escalationTrigger = null;
        for (var key : candidate.payload().orderedKeys()) {
            var role = roles.get(key);
            if (role == null) {
                continue;
            }
            var position = role.toPositionRole();
            if (position != null && positions.add(position)) {
                positionNames.add(role.name());
            }
            if (role == RecordFieldRole.ESCALATION) {
                escalationTrigger = candidate.payload().columns().get(key);
            }
        }
        builder.positions(positions).escalationTrigger(escalationTrigger);
        if (!positions.isEmpty()) {
            fields.put("positions", new FieldInterpretation(
                    "positions", InterpretationKind.DETERMINISTIC,
                    String.join(",", positionNames),
                    DETERMINISTIC_COLUMN_SPEC, 1,
                    null, provenance, VerificationState.VERIFIED, null));
        }
        if (escalationTrigger != null && !escalationTrigger.isBlank()) {
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.DETERMINISTIC, escalationTrigger,
                    DETERMINISTIC_COLUMN_SPEC, 1,
                    null, provenance, VerificationState.VERIFIED, null));
        }
    }

    private void interpretPlaybook(
            M4SemanticStates.PlaybookUnitState state,
            List<UUID> provenance,
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
            interpretEscalationTrigger(state, provenance, stateColumnsHasEscalation(state), builder, fields);
        }
        if (!positions.isEmpty()) {
            fields.put("positions", new FieldInterpretation(
                    "positions", InterpretationKind.BOUNDED_CLASSIFICATION,
                    positions.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElseThrow(),
                    M4SemanticSpecs.PLAYBOOK_POSITION_SPEC, M4SemanticSpecs.playbookPositions().version(),
                    execution.metadata().executionId(), provenance, VerificationState.NOT_VERIFIED, null));
        }
        if (requiresApproval != null) {
            fields.put("requiresApproval", new FieldInterpretation(
                    "requiresApproval", InterpretationKind.BOUNDED_CLASSIFICATION,
                    String.valueOf(requiresApproval),
                    M4SemanticSpecs.PLAYBOOK_POSITION_SPEC, M4SemanticSpecs.playbookPositions().version(),
                    execution.metadata().executionId(), provenance, VerificationState.NOT_VERIFIED, null));
        }
    }

    /**
     * Deterministic first: verbatim table cell values already carry the trigger. Only
     * free prose without a usable column value goes through generative extraction, and
     * the extracted value is independently verified before becoming a candidate field.
     */
    private void interpretEscalationTrigger(
            M4SemanticStates.PlaybookUnitState state,
            List<UUID> provenance,
            boolean deterministicValueAvailable,
            SemanticFieldsBuilder builder,
            Map<String, FieldInterpretation> fields) {
        var verbatim = deterministicValueAvailable
                ? state.columns().entrySet().stream()
                        .filter(entry -> entry.getValue() != null
                                && entry.getValue().toLowerCase(Locale.ROOT).contains("escalat"))
                        .map(Map.Entry::getValue)
                        .findFirst()
                : Optional.<String>empty();
        if (verbatim.isPresent()) {
            var value = verbatim.get();
            builder.escalationTrigger(value);
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.DETERMINISTIC, value,
                    DETERMINISTIC_COLUMN_SPEC, 1,
                    null, provenance, VerificationState.VERIFIED, null));
            return;
        }
        if (verbatim.isEmpty() && (state.text() == null || state.text().isBlank())) {
            // A unit with no prose has nothing to extract from and nothing to verify a claim
            // against. Recording the outcome keeps the candidate explicit and the source
            // lineage intact rather than silently omitting the field, and avoids asking a
            // model to extract from nothing.
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.GENERATIVE_EXTRACTION, "UNAVAILABLE",
                    M4ReasoningSpecs.ESCALATION_TRIGGER_SPEC, M4ReasoningSpecs.escalationTrigger().version(),
                    null, provenance, VerificationState.UNCERTAIN, null));
            return;
        }
        try {
            var extracted = generativeFieldExtractor.extractEscalationTrigger(state);
            if (extracted.isPresent()) {
                var verified = extracted.get();
                fields.put("escalationTrigger", new FieldInterpretation(
                        "escalationTrigger", InterpretationKind.GENERATIVE_EXTRACTION, verified.value(),
                        M4ReasoningSpecs.ESCALATION_TRIGGER_SPEC, M4ReasoningSpecs.escalationTrigger().version(),
                        verified.generationExecutionId(), provenance, verified.verification(),
                        verified.verificationExecutionId()));
                if (verified.verification() == VerificationState.VERIFIED) {
                    builder.escalationTrigger(verified.value());
                }
            }
        } catch (SemanticDecisionException e) {
            LoggerFactory.getLogger(SemanticCompilationService.class)
                    .warn("Escalation trigger extraction unavailable ({})", e.getClass().getSimpleName());
            fields.put("escalationTrigger", new FieldInterpretation(
                    "escalationTrigger", InterpretationKind.GENERATIVE_EXTRACTION, "UNAVAILABLE",
                    M4ReasoningSpecs.ESCALATION_TRIGGER_SPEC, M4ReasoningSpecs.escalationTrigger().version(),
                    null, provenance, VerificationState.UNCERTAIN, null));
        }
    }

    private boolean stateColumnsHasEscalation(M4SemanticStates.PlaybookUnitState state) {
        return state.columns().values().stream()
                .anyMatch(value -> value != null
                        && value.toLowerCase(Locale.ROOT).contains("escalat"));
    }

    private void interpretClauseVariant(
            M4SemanticStates.PlaybookUnitState state,
            List<UUID> provenance,
            SemanticFieldsBuilder builder,
            Map<String, FieldInterpretation> fields) {
        var execution = decisionService.evaluate(M4SemanticSpecs.clauseVariantRole(), state);
        if (!(execution.answers().get("variant_role") instanceof ChoiceAnswer answer)) {
            throw new ProviderProtocolException(
                    "Variant role answer is not a selection");
        }
        var selected = parsePositionRole(answer.selected());
        var probability = answer.probabilities().get(answer.selected());
        if (probability >= properties.variantRoleProposal()) {
            builder.variantRole(selected);
            fields.put("variantRole", new FieldInterpretation(
                    "variantRole", InterpretationKind.BOUNDED_CLASSIFICATION, selected.name(),
                    M4SemanticSpecs.CLAUSE_VARIANT_ROLE_SPEC, M4SemanticSpecs.clauseVariantRole().version(),
                    execution.metadata().executionId(), provenance, VerificationState.NOT_VERIFIED, null));
        }
    }

    private static PositionRole parsePositionRole(String value) {
        try {
            return PositionRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ProviderProtocolException(
                    "Unknown variant role label " + value);
        }
    }

    private void interpretScopeNarrowing(
            M4SemanticStates.PlaybookUnitState state,
            List<UUID> provenance,
            Map<String, FieldInterpretation> fields) {
        var execution = decisionService.evaluate(M4SemanticSpecs.scopeNarrowing(), state);
        var narrows = execution.answers().get("narrows_scope");
        var jurisdiction = execution.answers().get("jurisdiction");
        if (narrows instanceof YesNoAnswer yesNo && yesNo.probability() >= properties.scopeNarrowProposal()
                && jurisdiction instanceof ChoiceAnswer choice
                && choice.probabilities().get(choice.selected()) >= properties.jurisdictionProposal()) {
            fields.put("scope", new FieldInterpretation(
                    "scope", InterpretationKind.BOUNDED_CLASSIFICATION, choice.selected(),
                    M4SemanticSpecs.SCOPE_NARROWING_SPEC, M4SemanticSpecs.scopeNarrowing().version(),
                    execution.metadata().executionId(), provenance, VerificationState.NOT_VERIFIED, null));
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

    private static final class SemanticFieldsBuilder {
        private Set<PositionRole> positions = Set.of();
        private Boolean requiresApproval;
        private String escalationTrigger;
        private PositionRole variantRole;
        private Map<ConceptKey, Double> conceptProbabilities;

        SemanticFieldsBuilder(Map<ConceptKey, Double> conceptProbabilities) {
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
