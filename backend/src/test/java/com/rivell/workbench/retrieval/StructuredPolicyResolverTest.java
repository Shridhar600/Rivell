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
        var queryService = new KnowledgeQueryService(null, null, null) {
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
}
