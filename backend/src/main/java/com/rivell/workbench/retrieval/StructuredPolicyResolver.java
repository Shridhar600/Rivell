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

    /**
     * Resolves applicable published candidates by topic membership for evidence whose
     * topics come from contract classification rather than one concept key (design 6a):
     * the candidate's stored topic set must intersect the request topics, and the same
     * scope dimensions and effective window apply. Ordered primary-match first, then
     * shared-topic count, capped at {@code limit}.
     */
    public Resolution resolveByTopics(
            EvidenceRequest request, Set<CandidateType> candidateTypes,
            long asOfPublicationNumber, List<String> topicKeys, String primaryTopicKey,
            int limit) {
        if (asOfPublicationNumber < 1 || topicKeys.isEmpty()) {
            return new Resolution(List.of(), asOfPublicationNumber, false);
        }
        var profile = request.reviewProfile();
        var query = PublishedKnowledgeQuery.byTopics(
                request.organizationId(),
                asOfPublicationNumber,
                candidateTypes,
                Set.copyOf(topicKeys),
                primaryTopicKey,
                profile.contractPackKey(),
                profile.contractType() == null ? null : profile.contractType().name(),
                profile.ourSideRole() == null ? null : profile.ourSideRole().name(),
                profile.paperSource() == null ? null : profile.paperSource().name(),
                profile.jurisdiction() == Jurisdiction.UNSPECIFIED
                        ? null : profile.jurisdiction().name(),
                request.effectiveOn(),
                limit + 1); // one row past the cap reveals truncation
        // system-level read: the request's organization is authoritative and the
        // caller (EvidenceService) already enforced identity/authorization
        var candidates = knowledgeQueries.listPublishedCandidatesForOrganization(query);
        var truncated = candidates.size() > limit;
        var bounded = truncated ? candidates.subList(0, limit) : candidates;
        return new Resolution(positionsOnly(bounded, candidateTypes), asOfPublicationNumber,
                truncated);
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
    /**
     * Every applicable published candidate of the given families regardless of topic, for a
     * whole-contract read; same scope rules as {@link #resolve}.
     */
    public Resolution resolveAll(
            EvidenceRequest request, Set<CandidateType> candidateTypes,
            long asOfPublicationNumber, int limit) {
        if (asOfPublicationNumber < 1) {
            return new Resolution(List.of(), asOfPublicationNumber, false);
        }
        var profile = request.reviewProfile();
        var query = PublishedKnowledgeQuery.byTopics(
                request.organizationId(), asOfPublicationNumber, candidateTypes, Set.of(), null,
                profile.contractPackKey(),
                profile.contractType() == null ? null : profile.contractType().name(),
                profile.ourSideRole() == null ? null : profile.ourSideRole().name(),
                profile.paperSource() == null ? null : profile.paperSource().name(),
                profile.jurisdiction() == Jurisdiction.UNSPECIFIED
                        ? null : profile.jurisdiction().name(),
                request.effectiveOn(), limit);
        var candidates = knowledgeQueries.listPublishedCandidatesForOrganization(query);
        var truncated = candidates.size() > limit;
        return new Resolution(positionsOnly(truncated ? candidates.subList(0, limit) : candidates,
                candidateTypes), asOfPublicationNumber, truncated);
    }

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
                request.legalConcept().value(),
                profile.contractPackKey(),
                profile.contractType() == null ? null : profile.contractType().name(),
                profile.ourSideRole() == null ? null : profile.ourSideRole().name(),
                profile.paperSource() == null ? null : profile.paperSource().name(),
                profile.jurisdiction() == Jurisdiction.UNSPECIFIED
                        ? null : profile.jurisdiction().name(),
                request.effectiveOn());
        // system-level read: the request's organization is authoritative and the
        // caller (EvidenceService) already enforced identity/authorization
        var candidates = knowledgeQueries.listPublishedCandidatesForOrganization(query);
        var truncated = candidates.size() > query.limit();
        var bounded = truncated ? candidates.subList(0, query.limit()) : candidates;
        return new Resolution(positionsOnly(bounded, candidateTypes), asOfPublicationNumber,
                truncated);
    }

    /**
     * Council §A.4: a record with no role is never used as a position in review. A position is
     * a role the compile guarantees, so a published playbook rule that carries no position role
     * and no role-tagged stance value is left out of every position read.
     */
    private static List<com.rivell.workbench.knowledge.PublishedCandidate> positionsOnly(
            List<com.rivell.workbench.knowledge.PublishedCandidate> published,
            Set<CandidateType> candidateTypes) {
        if (!candidateTypes.contains(CandidateType.PLAYBOOK_RULE)) {
            return List.copyOf(published);
        }
        return published.stream()
                .filter(match -> match.candidate().carriesPosition())
                .toList();
    }
}
