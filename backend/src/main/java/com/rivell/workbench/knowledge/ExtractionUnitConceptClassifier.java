package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * The one M3 production semantic task: propose the bounded legal concepts an
 * extraction unit expresses. The result is a proposal — never organizational
 * truth, never persisted, never mutating candidate or publication state.
 */
public class ExtractionUnitConceptClassifier {

    private final KnowledgeRepository repository;
    private final SemanticDecisionService decisionService;
    private final DecisionSpec spec;
    private final ConceptClassificationProperties properties;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ExtractionUnitConceptClassifier(
            KnowledgeRepository repository,
            SemanticDecisionService decisionService,
            DecisionSpec spec,
            ConceptClassificationProperties properties,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.decisionService = decisionService;
        this.spec = spec;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public UnitConceptClassification classify(UUID artifactId, UUID unitId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_READ)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        var unit = repository.findUnit(unitId, identity.organizationId())
                .orElseThrow(() -> new KnowledgeUnitNotFoundException(unitId));
        if (!unit.artifactId().equals(artifactId)) {
            throw new KnowledgeUnitNotFoundException(unitId);
        }
        if (unit.contentText() == null || unit.contentText().isBlank()) {
            throw new IllegalArgumentException("The extraction unit has no text to classify");
        }

        var state = new ExtractionUnitConceptState(
                unit.contentText(), unit.kind(), unit.title());
        try {
            var execution = decisionService.evaluate(spec, state);
            return result(unit, execution);
        } catch (SemanticDecisionException e) {
            throw new ConceptClassificationUnavailableException(
                    "The semantic classification service is not available", e);
        }
    }

    private UnitConceptClassification result(ExtractionUnit unit, DecisionExecution execution) {
        var probabilities = new LinkedHashMap<LegalConcept, Double>();
        for (var concept : LegalConcept.values()) {
            var answer = execution.answers().get(ExtractionUnitConceptSpec.questionName(concept));
            if (answer instanceof com.rivell.workbench.intelligence.YesNoAnswer yesNo) {
                probabilities.put(concept, yesNo.probability());
            } else {
                throw new ConceptClassificationUnavailableException(
                        "The classification result is incomplete for concept " + concept);
            }
        }
        var proposed = probabilities.entrySet().stream()
                .filter(entry -> entry.getValue() >= properties.conceptThreshold())
                .map(Map.Entry::getKey)
                .toList();
        return new UnitConceptClassification(
                unit.id(), unit.artifactId(), List.copyOf(proposed), probabilities,
                proposed.isEmpty(), execution.metadata());
    }
}
