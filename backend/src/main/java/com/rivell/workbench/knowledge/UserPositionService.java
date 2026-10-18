package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * "Make this a position" (design 2026-10-12 §9): a person corrects a part of a playbook or clause
 * library that Rivell did not take as a position. The part's candidate is rebuilt from the stored
 * source through the same compiler region and interpreted like at upload; it starts PENDING and is
 * reviewed and published like any other, so the publications stay the versions. The machine's
 * record kind is kept beside the person's correction.
 */
public class UserPositionService {

    private static final Set<ArtifactType> GATED = Set.of(ArtifactType.PLAYBOOK,
            ArtifactType.CLAUSE_LIBRARY);

    private final KnowledgeRepository repository;
    private final SemanticCompilationService semanticCompilation;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public UserPositionService(
            KnowledgeRepository repository,
            SemanticCompilationService semanticCompilation,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.semanticCompilation = semanticCompilation;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public List<KnowledgeCandidate> makePosition(UUID artifactId, UUID unitId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_REVIEW)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var artifact = repository.findArtifact(artifactId, organizationId)
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(artifactId));
        var unit = repository.findUnit(unitId, organizationId)
                .filter(found -> found.artifactId().equals(artifactId))
                .orElseThrow(() -> new KnowledgeUnitNotFoundException(unitId));
        if (!GATED.contains(artifact.artifactType())) {
            throw new UnitNotMarkableException("only a playbook or a clause library sorts its parts "
                    + "into positions; every part of this file with text is already a candidate");
        }
        if (unit.override() != null || repository.loadCandidates(artifactId, organizationId)
                .stream().anyMatch(candidate -> candidate.extractionUnitId().equals(unitId))) {
            throw new UnitNotMarkableException("this part already holds a position");
        }
        // the part is the region over exactly its source nodes, wherever the rebuild lists it;
        // stored provenance comes back as a set, so the nodes are compared as sets
        var sourceNodes = Set.copyOf(unit.sourceNodeIds());
        var region = KnowledgeStructure.build(repository.loadStructure(artifactId, organizationId))
                .regions().stream()
                .filter(candidate -> Set.copyOf(candidate.toUnit(artifactId, unit.ordinal())
                        .sourceNodeIds()).equals(sourceNodes))
                .findFirst()
                .orElseThrow(() -> new UnitNotMarkableException(
                        "the source no longer matches this part"));
        // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would be
        // stored rounded, so the returned candidate would not match a later read of it.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var raw = region.toCandidates(artifact, unit,
                ApplicabilityScope.fromManifest(artifact.manifest()), now);
        if (raw.isEmpty()) {
            throw new UnitNotMarkableException("this part has no text to make a position from");
        }
        // model calls run with no open transaction, as at upload
        var compiled = semanticCompilation.compileUserPosition(artifact, unit, raw,
                repository.findPublishedCandidates(organizationId));
        transactionTemplate.executeWithoutResult(status -> {
            if (!repository.overrideRecordKind(unitId, organizationId,
                    KnowledgeRecordKind.POSITION, identity.userId(), now)) {
                throw new UnitNotMarkableException("this part already holds a position");
            }
            repository.insertCandidates(artifactId, compiled);
        });
        return compiled;
    }
}
