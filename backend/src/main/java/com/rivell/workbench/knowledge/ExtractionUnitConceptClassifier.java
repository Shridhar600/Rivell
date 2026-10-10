package com.rivell.workbench.knowledge;

import java.util.UUID;

import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.taxonomy.TopicClassification;
import com.rivell.workbench.taxonomy.TopicClassifier;

/**
 * The one M3 production semantic task: propose the bounded legal topics an
 * extraction unit expresses. The result is a proposal — never organizational
 * truth, never persisted, never mutating candidate or publication state.
 */
public class ExtractionUnitConceptClassifier {

    private final KnowledgeRepository repository;
    private final TopicClassifier topicClassifier;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ExtractionUnitConceptClassifier(
            KnowledgeRepository repository,
            TopicClassifier topicClassifier,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.topicClassifier = topicClassifier;
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

        TopicClassification classification;
        try {
            classification = topicClassifier.classify(unit.title(), unit.contentText());
        } catch (SemanticDecisionException | IllegalArgumentException e) {
            throw new ConceptClassificationUnavailableException(
                    "The semantic classification service is not available", e);
        }
        return new UnitConceptClassification(
                unit.id(), unit.artifactId(), classification.topics(),
                classification.probabilities(), classification.topics().isEmpty(),
                classification.taxonomyVersion(), classification.decisionExecutions(),
                classification.questions());
    }
}
