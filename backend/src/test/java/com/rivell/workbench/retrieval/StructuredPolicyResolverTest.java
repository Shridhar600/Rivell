package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.PublishedCandidate;
import com.rivell.workbench.knowledge.PublishedKnowledgeQuery;
import com.rivell.workbench.taxonomy.CoreConcepts;

class StructuredPolicyResolverTest {

    @Test
    void namedJurisdictionsReachTheQueryAndUnspecifiedStaysTheWildcard() {
        var queries = new ArrayList<PublishedKnowledgeQuery>();
        var queryService = new KnowledgeQueryService(null, null, null, java.time.Clock.systemUTC()) {
            @Override
            public List<PublishedCandidate> listPublishedCandidatesForOrganization(
                    PublishedKnowledgeQuery query) {
                queries.add(query);
                return List.of();
            }
        };
        var resolver = new StructuredPolicyResolver(queryService);
        var organizationId = UUID.randomUUID();

        resolver.resolve(request(organizationId, Jurisdiction.NEW_ZEALAND), Set.of(), 1);
        resolver.resolve(request(organizationId, Jurisdiction.OTHER), Set.of(), 1);
        resolver.resolve(request(organizationId, Jurisdiction.UNSPECIFIED), Set.of(), 1);

        assertThat(queries).extracting(PublishedKnowledgeQuery::jurisdiction)
                .containsExactly("NEW_ZEALAND", "OTHER", null);
    }

    private static EvidenceRequest request(UUID organizationId, Jurisdiction jurisdiction) {
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, jurisdiction, "MSA_PACK");
        return EvidenceRequest.forClauseReview(organizationId, CoreConcepts.GOVERNING_LAW,
                UUID.randomUUID(), 1, UUID.randomUUID(),
                "This Agreement is governed by the laws of New Zealand.", profile,
                LocalDate.of(2026, 1, 1), KnowledgePin.LATEST);
    }

    @Test
    void aPublishedPlaybookRuleWithNoRoleIsStillReadAsAPosition() {
        // prose playbooks (one rule per paragraph, no stance labels) compile with no role; the
        // whole rule is the position. Leaving them out lost 10 dev issues (release-4 eval).
        var organizationId = UUID.randomUUID();
        var noRole = candidate(Set.of(), java.util.Map.of(), "Indemnities",
                "The supplier must indemnify us for third-party IP claims without a cap.");
        var position = candidate(Set.of(com.rivell.workbench.knowledge.PositionRole.PREFERRED),
                java.util.Map.of(), "Limitation of liability",
                "Preferred: cap at the fees paid in the preceding 12 months.");
        var queryService = new KnowledgeQueryService(null, null, null, java.time.Clock.systemUTC()) {
            @Override
            public List<PublishedCandidate> listPublishedCandidatesForOrganization(
                    PublishedKnowledgeQuery query) {
                return List.of(
                        new PublishedCandidate(noRole, UUID.randomUUID(), 1),
                        new PublishedCandidate(position, UUID.randomUUID(), 1));
            }
        };
        var resolver = new StructuredPolicyResolver(queryService);

        var resolution = resolver.resolveAll(request(organizationId, Jurisdiction.UNSPECIFIED),
                Set.of(com.rivell.workbench.knowledge.CandidateType.PLAYBOOK_RULE), 1, 10);

        assertThat(resolution.candidates())
                .extracting(candidate -> candidate.rawLabel())
                .containsExactly("Indemnities", "Limitation of liability");
    }

    private static com.rivell.workbench.knowledge.KnowledgeCandidate candidate(
            Set<com.rivell.workbench.knowledge.PositionRole> positions,
            java.util.Map<String, String> columns, String label, String content) {
        var now = java.time.Instant.now();
        var scope = new com.rivell.workbench.knowledge.ApplicabilityScope(
                null, Set.of(), null, null, null, null, null);
        return new com.rivell.workbench.knowledge.KnowledgeCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                com.rivell.workbench.knowledge.CandidateType.PLAYBOOK_RULE,
                com.rivell.workbench.knowledge.ReviewStatus.PUBLISHED, label, content,
                new com.rivell.workbench.knowledge.CandidatePayload(columns),
                CoreConcepts.LIMITATION_OF_LIABILITY.value(),
                List.of(CoreConcepts.LIMITATION_OF_LIABILITY.value()),
                null, null, null,
                new com.rivell.workbench.knowledge.SemanticFields(positions, null, null, null,
                        java.util.Map.of()),
                null, List.of(), null, null, null, scope, List.of(), null, null, null, null,
                now, now, null);
    }
}
