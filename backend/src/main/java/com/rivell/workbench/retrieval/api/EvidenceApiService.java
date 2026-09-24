package com.rivell.workbench.retrieval.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.retrieval.ClauseNotFoundException;
import com.rivell.workbench.retrieval.ClauseNotClassifiedException;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.EnrichmentNotReadyException;
import com.rivell.workbench.retrieval.KnowledgePin;

/**
 * Composes the product-facing evidence bundle response from the domain bundle
 * (M6 developer surface until M7 provides the review consumer).
 */
public class EvidenceApiService {

    private final EvidenceService evidenceService;
    private final IdentityProvider identityProvider;
    private final com.rivell.workbench.contracts.ContractEnrichmentService enrichment;

    public EvidenceApiService(
            EvidenceService evidenceService,
            IdentityProvider identityProvider,
            com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
        this.evidenceService = evidenceService;
        this.identityProvider = identityProvider;
        this.enrichment = enrichmentService;
    }

    public RetrievalResponses.EvidenceBundleResponse build(
            RetrievalRequests.EvidenceBuildRequest request) {
        var identity = identityProvider.currentIdentity();
        var semantics = enrichment.getSemantics(request.contractId(), request.versionNumber());
        if (semantics.enrichmentStatus() != com.rivell.workbench.contracts.EnrichmentStatus.READY) {
            throw new EnrichmentNotReadyException(request.contractId(), request.versionNumber());
        }
        var subject = semantics.clauses().stream()
                .filter(clause -> clause.id().equals(request.clauseId()))
                .findFirst()
                .orElseThrow(() -> new ClauseNotFoundException(request.clauseId()));
        var concept = subject.primaryConcept()
                .orElseThrow(() -> new ClauseNotClassifiedException(request.clauseId()));
        var domainRequest = EvidenceRequest.forClauseReview(
                identity.organizationId(),
                concept,
                request.contractId(),
                request.versionNumber(),
                request.clauseId(),
                subject.contentText(),
                semantics.reviewProfile(),
                LocalDate.now(),
                KnowledgePin.LATEST);
        var bundle = evidenceService.build(domainRequest);
        return toResponse(bundle);
    }

    private RetrievalResponses.EvidenceBundleResponse toResponse(EvidenceBundle bundle) {
        var items = new ArrayList<RetrievalResponses.EvidenceItemResponse>();
        for (var item : bundle.items()) {
            items.add(new RetrievalResponses.EvidenceItemResponse(
                    item.evidenceId(),
                    item.authorityClass().name(),
                    item.sourceType(),
                    item.sourceArtifactId(),
                    item.sourceCandidateId(),
                    item.sourceContractVersionId(),
                    item.sourceClauseId(),
                    item.legalConcept() == null ? null : item.legalConcept().name(),
                    item.text(),
                    item.policyFacts() == null ? null : new RetrievalResponses.PolicyFactsResponse(
                            item.policyFacts().positions().stream()
                                    .map(Enum::name)
                                    .collect(java.util.stream.Collectors.toSet()),
                            item.policyFacts().requiresApproval(),
                            item.policyFacts().escalationTrigger()),
                    item.contractFacts(),
                    item.retrievalReason(),
                    new RetrievalResponses.LaneInfoResponse(
                            item.laneInfo().lexicalRank(),
                            item.laneInfo().vectorRank(),
                            item.laneInfo().fusedRank(),
                            item.laneInfo().fusedScore()),
                    item.semanticAssessment() == null
                            ? null
                            : new RetrievalResponses.SemanticAssessmentResponse(
                                    item.semanticAssessment().judgments(),
                                    item.semanticAssessment().decisionSpecId(),
                                    item.semanticAssessment().decisionSpecVersion()),
                    item.policyCompatibility() == null
                            ? null
                            : item.policyCompatibility().name(),
                    item.provenanceNodeIds(),
                    item.publicationId(),
                    item.publicationNumber() == null ? null
                            : item.publicationNumber().longValue()));
        }
        return new RetrievalResponses.EvidenceBundleResponse(
                bundle.id(),
                new RetrievalResponses.EvidenceRequestSummary(
                        bundle.request().purpose().name(),
                        bundle.request().legalConcept().name(),
                        bundle.request().contractId(),
                        bundle.request().versionNumber(),
                        bundle.request().clauseId(),
                        bundle.request().effectiveOn().toString(),
                        bundle.request().knowledgePin().publicationNumber()),
                List.copyOf(items),
                bundle.sufficiency().name(),
                bundle.traceId(),
                bundle.degradationWarnings());
    }
}
