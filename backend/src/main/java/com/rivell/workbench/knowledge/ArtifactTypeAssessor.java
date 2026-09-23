package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.SemanticDecisionService;

/**
 * Machine assessment of the artifact class a source most resembles (Jev Choice over the
 * bounded artifact classes). The human declaration stays authoritative; the assessment
 * produces advisory evidence: MATCH, POSSIBLE_MISMATCH, or honest AMBIGUOUS (mixed sources).
 */
public class ArtifactTypeAssessor {

    private final SemanticDecisionService decisionService;
    private final SemanticPolicyProperties properties;

    public ArtifactTypeAssessor(SemanticDecisionService decisionService,
            SemanticPolicyProperties properties) {
        this.decisionService = decisionService;
        this.properties = properties;
    }

    public ArtifactTypeAssessment assess(KnowledgeArtifact artifact, List<String> sampleTexts) {
        var execution = decisionService.evaluate(
                M4SemanticSpecs.artifactTypeAssessment(),
                new M4SemanticStates.ArtifactAssessmentState(
                        artifact.artifactType().name(), bounded(sampleTexts)));
        var answer = (com.rivell.workbench.intelligence.ChoiceAnswer)
                execution.answers().get("artifact_class");
        var assessedType = ArtifactType.valueOf(answer.selected());
        var probability = answer.probabilities().get(answer.selected());
        var state = classify(assessedType, artifact.artifactType(), answer);
        List<String> warnings = assessedType != artifact.artifactType()
                ? List.of("HUMAN_DECLARED_TYPE_DIFFERS_FROM_MACHINE_ASSESSMENT")
                : List.of();
        return new ArtifactTypeAssessment(
                assessedType, probability, state,
                M4SemanticSpecs.ARTIFACT_TYPE_SPEC,
                M4SemanticSpecs.artifactTypeAssessment().version(),
                execution.metadata().executionId(), warnings);
    }

    private ArtifactTypeAssessment.AssessmentState classify(
            ArtifactType assessed, ArtifactType declared, ChoiceAnswer answer) {
        var probability = answer.probabilities().get(answer.selected());
        var runnerUp = answer.probabilities().values().stream()
                .filter(value -> !value.equals(answer.probabilities().get(answer.selected())))
                .max(Double::compare)
                .orElse(0.0);
        if (probability < properties.artifactTypeMatch()
                || probability - runnerUp < properties.artifactTypeAmbiguityGap()) {
            return ArtifactTypeAssessment.AssessmentState.AMBIGUOUS;
        }
        return assessed == declared
                ? ArtifactTypeAssessment.AssessmentState.MATCH
                : ArtifactTypeAssessment.AssessmentState.POSSIBLE_MISMATCH;
    }

    private static List<String> bounded(List<String> sampleTexts) {
        return sampleTexts.stream()
                .filter(text -> text != null && !text.isBlank())
                .limit(3)
                .map(text -> text.length() > 800 ? text.substring(0, 800) : text)
                .toList();
    }
}
