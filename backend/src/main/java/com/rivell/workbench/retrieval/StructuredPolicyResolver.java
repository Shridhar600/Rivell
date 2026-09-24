package com.rivell.workbench.retrieval;

import java.util.List;
import java.util.Set;

import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.PublishedKnowledgeQuery;

/**
 * Deterministic resolution of published knowledge through structured lookup only
 * (Architecture Decision #8.1). No embeddings, no similarity. Zero applicable rules
 * stays missing; two or more materially applicable rules stay ambiguous — neither is
 * ever resolved through similarity.
 */
public class StructuredPolicyResolver {

    private final KnowledgeQueryService knowledgeQueries;

    public StructuredPolicyResolver(KnowledgeQueryService knowledgeQueryService) {
        this.knowledgeQueries = knowledgeQueryService;
    }

    public record Resolution(
            List<com.rivell.workbench.knowledge.PublishedCandidate> published,
            long asOfPublication,
            boolean truncated) {

        public List<KnowledgeCandidate> candidates() {
            return published.stream()
                    .map(com.rivell.workbench.knowledge.PublishedCandidate::candidate)
                    .toList();
        }

        public boolean ambiguous() {
            return published.size() > 1 || truncated;
        }

        public boolean missing() {
            return published.isEmpty();
        }
    }

    /**
     * Resolves applicable published candidates of the given families for the request
     * context: concept + scope dimensions (wildcard where the knowledge side declares
     * nothing) + effective window containing the request's effective date, all within
     * the pinned publication set.
     */
    public Resolution resolve(
            EvidenceRequest request, Set<CandidateType> candidateTypes,
            long asOfPublicationNumber) {
        if (asOfPublicationNumber < 1) {
            return new Resolution(List.of(), asOfPublicationNumber, false);
        }
        var profile = request.reviewProfile();
        var query = PublishedKnowledgeQuery.unpublishedNever(
                request.organizationId(),
                asOfPublicationNumber,
                candidateTypes,
                request.legalConcept().name(),
                profile.contractPackKey(),
                profile.contractType() == null ? null : profile.contractType().name(),
                profile.ourSideRole() == null ? null : profile.ourSideRole().name(),
                profile.paperSource() == null ? null : profile.paperSource().name(),
                profile.jurisdiction() == Jurisdiction.UNSPECIFIED
                        ? null : profile.jurisdiction().name(),
                request.effectiveOn());
        var candidates = knowledgeQueries.findPublishedCandidatesAsOf(query);
        var truncated = candidates.size() > query.limit();
        var bounded = truncated ? candidates.subList(0, query.limit()) : candidates;
        return new Resolution(List.copyOf(bounded), asOfPublicationNumber, truncated);
    }
}
