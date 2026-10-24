package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * The two Jev junctions of knowledge record compilation (design §5 items 2 and 3):
 * unknown field labels resolved once per artifact, and one record-kind decision per
 * record. Both are bounded, versioned decisions; neither ever drops a record — the
 * caller fails open to a candidate when the provider is unavailable.
 */
final class KnowledgeRecordSemantics {

    static final String FIELD_ROLE_SPEC = "knowledge-field-role";
    static final String RECORD_KIND_SPEC = "knowledge-record-kind";
    static final int FIELD_ROLE_VERSION = 2;
    static final int RECORD_KIND_VERSION = 3;

    private static final int MAX_RECORD_TEXT = 2000;
    private static final int MAX_SAMPLE_VALUE = 160;
    private static final java.util.Set<RecordFieldRole> STANCE_ROLES = java.util.EnumSet.of(
            RecordFieldRole.PREFERRED, RecordFieldRole.FALLBACK, RecordFieldRole.PROHIBITED);

    private final SemanticDecisionService decisions;

    KnowledgeRecordSemantics(SemanticDecisionService decisions) {
        this.decisions = decisions;
    }

    /**
     * One decision per artifact: a Choice question per distinct unknown label over the
     * field roles plus OTHER. Provider failure is reported as unavailable; unknown labels then
     * simply contribute no role, and the caller records the semantic gap.
     */
    record LabelRoles(Map<String, RecordFieldRole> roles, boolean available) {

        static LabelRoles none() {
            return new LabelRoles(Map.of(), true);
        }

        static LabelRoles failed() {
            return new LabelRoles(Map.of(), false);
        }
    }

    LabelRoles resolveUnknownLabels(
            KnowledgeArtifact artifact, List<KnowledgeCandidate> candidates) {
        var unknown = new LinkedHashMap<String, String>();
        for (var candidate : candidates) {
            for (var entry : candidate.payload().columns().entrySet()) {
                var label = entry.getKey();
                if (RecordLabelRoles.known(label).isPresent()) {
                    continue;
                }
                var key = RecordLabelRoles.normalize(label);
                if (key.isEmpty() || key.matches("column \\d+") || unknown.containsKey(key)) {
                    continue;
                }
                unknown.put(key, label);
            }
        }
        if (unknown.isEmpty()) {
            return LabelRoles.none();
        }
        var labels = new ArrayList<LabelSample>(unknown.size());
        for (var entry : unknown.entrySet()) {
            labels.add(new LabelSample(entry.getValue(), sampleValue(candidates, entry.getValue())));
        }
        var spec = fieldRoleSpec(labels);
        try {
            var execution = decisions.evaluate(spec, new FieldRoleState(labels));
            var resolved = new LinkedHashMap<String, RecordFieldRole>();
            for (int index = 0; index < labels.size(); index++) {
                var answer = execution.answers().get("label_" + (index + 1));
                if (!(answer instanceof ChoiceAnswer choice)) {
                    throw new ProviderProtocolException(
                            "Missing field role answer for label " + labels.get(index).label());
                }
                resolved.put(RecordLabelRoles.normalize(labels.get(index).label()),
                        roleOf(choice.selected()));
            }
            return new LabelRoles(Map.copyOf(resolved), true);
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeRecordSemantics.class)
                    .warn("Field role decision unavailable for artifact {} ({} {})",
                            artifact.id(), e.getClass().getSimpleName(), e.getMessage());
            return LabelRoles.failed();
        }
    }

    /**
     * The kind of one record. A record with a PREFERRED, FALLBACK or PROHIBITED field is a
     * POSITION without a call when that label is in the code synonym table. Any other
     * record goes through one Choice.
     *
     * @throws com.rivell.workbench.intelligence.SemanticDecisionException when the
     *     provider fails; the caller fails open and keeps the record as a candidate.
     */
    Optional<KnowledgeRecordKind> decideKind(
            KnowledgeArtifact artifact, ExtractionUnit unit, KnowledgeCandidate candidate,
            Map<String, RecordFieldRole> roles) {
        // explicit before inferred: a label the source states is a position without a call. A
        // clause library's "Variant: Preferred" is a wording variant, not a playbook position.
        var labelled = candidate.payload().columns().isEmpty()
                ? LabelledFieldStage.parse(unit.title(), unit.contentText(), Map.of())
                : LabelledFieldStage.parse(unit.title(), null, candidate.payload().columns());
        if (artifact.artifactType() == ArtifactType.PLAYBOOK
                && labelled.statedRoles().stream().anyMatch(role ->
                        role == PositionRole.PREFERRED || role == PositionRole.FALLBACK
                                || role == PositionRole.PROHIBITED)) {
            return Optional.of(KnowledgeRecordKind.POSITION);
        }
        // Only a code-known stance label proves a position; a label the model resolved
        // (e.g. an approval matrix's "Situation" read as PREFERRED) still needs the kind call.
        var knownStance = roles.entrySet().stream().anyMatch(entry ->
                STANCE_ROLES.contains(entry.getValue())
                        && RecordLabelRoles.known(entry.getKey()).isPresent());
        if (knownStance) {
            return Optional.of(KnowledgeRecordKind.POSITION);
        }
        // a record titled only as a note ("Drafting note", "Note on the fallback") explains
        // wording; the model reads its imperative text as a stance
        if (KnowledgeTableShaping.isLabelLike(candidate.rawLabel())
                && RecordLabelRoles.known(candidate.rawLabel())
                        .filter(role -> role == RecordFieldRole.COMMENTARY).isPresent()) {
            return Optional.of(KnowledgeRecordKind.GUIDANCE);
        }
        var state = new RecordKindState(
                artifact.filename(),
                unit.title(),
                candidate.rawLabel(),
                truncate(candidate.effectiveContentText(), MAX_RECORD_TEXT));
        var execution = decisions.evaluate(recordKindSpec(), state);
        var answer = execution.answers().get("record_kind");
        if (!(answer instanceof ChoiceAnswer choice)) {
            throw new ProviderProtocolException("Missing record kind answer");
        }
        try {
            return Optional.of(KnowledgeRecordKind.valueOf(choice.selected()));
        } catch (IllegalArgumentException e) {
            throw new ProviderProtocolException(
                    "Unknown record kind " + choice.selected());
        }
    }

    private static RecordFieldRole roleOf(String selected) {
        try {
            return RecordFieldRole.valueOf(selected);
        } catch (IllegalArgumentException e) {
            return RecordFieldRole.OTHER;
        }
    }

    private static String sampleValue(List<KnowledgeCandidate> candidates, String label) {
        for (var candidate : candidates) {
            var value = candidate.payload().columns().get(label);
            if (value != null && !value.isBlank()) {
                return truncate(value, MAX_SAMPLE_VALUE);
            }
        }
        return "";
    }

    private static DecisionSpec fieldRoleSpec(List<LabelSample> labels) {
        var questions = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticQuestion>();
        for (int index = 0; index < labels.size(); index++) {
            var label = labels.get(index);
            var sample = label.sampleValue() == null || label.sampleValue().isBlank()
                    ? "no sample value"
                    : "a sample value is \"" + label.sampleValue() + "\"";
            questions.put("label_" + (index + 1), new ChoiceQuestion(
                    "What role does the field label \"" + label.label() + "\" play in this "
                            + "knowledge record (" + sample + ")?",
                    roleCriteria()));
        }
        return new DecisionSpec(FIELD_ROLE_SPEC, FIELD_ROLE_VERSION,
                "Classify the role of an unrecognised field label in a knowledge record so its "
                        + "values reach the right semantic field.",
                FieldRoleState.SCHEMA_VERSION, new QuestionPack(questions));
    }

    private static Map<String, String> roleCriteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("PREFERRED", "The organisation's standard or preferred position.");
        criteria.put("FALLBACK", "An acceptable fallback position.");
        criteria.put("PROHIBITED", "A walk-away or red line the organisation must not accept.");
        criteria.put("ESCALATION", "Who must approve a deviation, or how to escalate.");
        criteria.put("COMMENTARY", "Drafting notes, rationale, or when-to-use guidance.");
        criteria.put("REFERENCE", "A reference, identifier, or number for the record.");
        criteria.put("APPLICABILITY", "What the record applies to (contract types, scope).");
        criteria.put("TOPIC", "The clause topic or subject of the record.");
        criteria.put("WORDING", "Approved contract wording.");
        criteria.put("OTHER", "None of the above.");
        return criteria;
    }

    private static DecisionSpec recordKindSpec() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("POSITION", "States the organisation's stance on a contract term: what it "
                + "prefers, accepts as a fallback, or refuses.");
        criteria.put("WORDING", "Contains approved contract wording to use, one or more "
                + "variants, even when notes on when or how to use it come with the wording.");
        criteria.put("ESCALATION", "Who must approve a deviation, or how to escalate.");
        criteria.put("GUIDANCE", "Only explains how to use the document, a process or a "
                + "wording variant, or only defines terms: it contains no contract wording of its "
                + "own and takes no stance on a contract term.");
        criteria.put("DOCUMENT_CONTROL", "Title, version, owner, classification, review dates, "
                + "change history, contacts.");
        criteria.put("OTHER", "None of the above.");
        return new DecisionSpec(RECORD_KIND_SPEC, RECORD_KIND_VERSION,
                "Classify what one knowledge record is, so only positions and approved wording "
                        + "become candidates.",
                RecordKindState.SCHEMA_VERSION,
                new QuestionPack(Map.of("record_kind", new ChoiceQuestion(
                        "What is this record?", criteria))));
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** State of the per-artifact unknown-label decision: labels plus one sample value. */
    record LabelSample(String label, String sampleValue) {
    }

    record FieldRoleState(List<LabelSample> labels) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            return Map.of("labels", labels);
        }
    }

    /** State of one record-kind decision: only the slice the judgment needs. */
    record RecordKindState(
            String documentTitle, String sectionPath, String recordTitle, String recordText)
            implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new LinkedHashMap<String, Object>();
            if (documentTitle != null && !documentTitle.isBlank()) {
                payload.put("documentTitle", documentTitle);
            }
            if (sectionPath != null && !sectionPath.isBlank()) {
                payload.put("sectionPath", sectionPath);
            }
            if (recordTitle != null && !recordTitle.isBlank()) {
                payload.put("recordTitle", recordTitle);
            }
            payload.put("recordText", recordText == null ? "" : recordText);
            return payload;
        }
    }
}
