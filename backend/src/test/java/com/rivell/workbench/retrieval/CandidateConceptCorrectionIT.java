package com.rivell.workbench.retrieval;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Foundation flow: an adversarial precedent whose machine classification
 * is absent is corrected by human review, published with the effective concept,
 * and M6 retrieval discovers it through published truth alone (no retrieval-side
 * override). Machine history stays inspectable; clear/change respond correctly;
 * tenant isolation holds.
 */
@SpringBootTest
@Testcontainers
class CandidateConceptCorrectionIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
        registry.add("rivell.retrieval.indexing-enabled", () -> "true");
        registry.add("rivell.intelligence.embeddings.api-key", () -> "fake-key");
        registry.add("rivell.intelligence.embeddings.provider", () -> "fake");
        registry.add("rivell.intelligence.embeddings.model", () -> "fake-embedding-model");
        registry.add("rivell.intelligence.embeddings.dimensions", () -> "8");
    }

    /** Semantic compilation classifies nothing (machine concept stays absent). */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoConceptIntelligenceConfiguration {

        @Bean
        @Primary
        com.rivell.workbench.intelligence.ModelPolicy fakeModelPolicy() {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-gen-model")
                    : new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider noConceptProvider() {
            var corpusCues = new CorpusScriptedProvider();
            return new SemanticDecisionProvider() {
                @Override
                public String id() {
                    return "fake";
                }

                @Override
                public ProviderExecutionResult execute(DecisionExecutionRequest request) {
                    if (request.pack().questions().keySet().stream()
                            .noneMatch(name -> name.startsWith("topic_"))) {
                        return corpusCues.execute(request);
                    }
                    var answers = new java.util.LinkedHashMap<String, SemanticAnswer>();
                    for (var entry : request.pack().questions().entrySet()) {
                        answers.put(entry.getKey(),
                                entry.getKey().startsWith("topic_")
                                        ? new com.rivell.workbench.intelligence.YesNoAnswer(0.02)
                                        : corpusCues.execute(request).answers().get(entry.getKey()));
                    }
                    return new ProviderExecutionResult(
                            answers, "fake-resolved-noconcept", new TokenUsage(100, 10));
                }
            };
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.EmbeddingProvider fakeEmbeddingProvider() {
            return new FakeEmbeddingProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        EvidenceApiServiceIT.MutableIdentity mutableIdentityProvider() {
            return new EvidenceApiServiceIT.MutableIdentity();
        }
    }

    @Autowired
    EvidenceApiServiceIT.MutableIdentity identityProvider;

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    KnowledgeQueryService knowledgeQueries;

    @Autowired
    EvidenceService evidenceService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    private record IngestedPrecedent(UUID artifactId, KnowledgeCandidate clause) {
    }

    private IngestedPrecedent ingestUnclassifiedPrecedent(UUID organizationId) throws Exception {
        var artifact = ingestionService.ingest(
                "m6-precedent-incompatible.docx",
                com.rivell.workbench.contracts.ContractIngestionService.DOCX_CONTENT_TYPE,
                com.rivell.workbench.knowledge.ArtifactType.EXECUTED_PRECEDENT,
                new com.rivell.workbench.knowledge.ArtifactManifest(
                        java.util.Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party",
                        "US", "en", null, null, null),
                new ByteArrayInputStream(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                        "src/test/resources/fixtures", "m6-precedent-incompatible.docx"))));
        var candidates = knowledgeQueries.getCandidates(artifact.id());
        var clause = candidates.stream()
                .filter(candidate -> candidate.legalConceptKey() == null
                        && "Limitation of Liability".equals(candidate.rawLabel()))
                .findFirst().orElseThrow();
        return new IngestedPrecedent(artifact.id(), clause);
    }

    @Test
    void humanConceptCorrectionPublishesAndM6RetrievesByEffectiveConcept() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);
        assertThat(precedent.clause().legalConceptKey()).isNull();

        // human review corrects the concept before publication
        var corrected = reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.LIMITATION_OF_LIABILITY.value());
        assertThat(corrected.legalConceptKey()).isNull();
        assertThat(corrected.conceptCorrection())
                .isEqualTo(CoreConcepts.LIMITATION_OF_LIABILITY.value());
        assertThat(corrected.effectiveConceptKey())
                .isEqualTo(CoreConcepts.LIMITATION_OF_LIABILITY.value());

        reviewService.correctConcept(precedent.clause().id(), "");
        var cleared = knowledgeQueries.getCandidate(precedent.clause().id());
        assertThat(cleared.conceptCorrection()).isEqualTo("");
        assertThat(cleared.effectiveConceptKey()).isNull();

        // change back, then approve + publish — publication consumes the effective concept
        reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.LIMITATION_OF_LIABILITY.value());
        var approved = reviewService.approve(precedent.clause().id(), null);
        var publication = publicationService.publish(
                java.util.List.of(approved.id()), java.util.Map.of(), "concept correction flow");
        assertThat(publication.publicationNumber()).isPositive();

        var published = knowledgeQueries.getCandidate(precedent.clause().id());
        assertThat(published.effectiveConceptKey())
                .isEqualTo(CoreConcepts.LIMITATION_OF_LIABILITY.value());
        assertThat(published.legalConceptKey()).isNull();

        // M6 retrieval discovers it through published truth alone
        var request = EvidenceRequest.forClauseReview(
                organizationId, CoreConcepts.LIMITATION_OF_LIABILITY,
                UUID.randomUUID(), 1, UUID.randomUUID(),
                "Supplier's total aggregate liability shall not exceed three times "
                        + "the fees paid in the preceding twelve months.",
                new com.rivell.workbench.contracts.ReviewProfile(
                        com.rivell.workbench.contracts.ContractType.MSA,
                        com.rivell.workbench.contracts.PartyRole.CUSTOMER,
                        com.rivell.workbench.contracts.PaperSource.THIRD_PARTY,
                        com.rivell.workbench.contracts.Jurisdiction.US),
                LocalDate.of(2026, 9, 25), KnowledgePin.LATEST);
        var bundle = evidenceService.build(request);
        assertThat(bundle.precedentItems())
                .anySatisfy(item -> {
                    assertThat(item.sourceCandidateId()).isEqualTo(approved.id());
                    assertThat(item.legalConcept()).isEqualTo(CoreConcepts.LIMITATION_OF_LIABILITY);
                });
    }

    /**
     * PostgreSQL timestamptz keeps microseconds, and a correction records who and when.
     * The candidate it returns must carry what was stored, so it equals a later read of it.
     */
    @Test
    void aCorrectedConceptReturnsTheTimestampsTheDatabaseKeeps() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);

        var corrected = reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.LIMITATION_OF_LIABILITY.value());

        assertThat(corrected.updatedAt().getNano() % 1000).isZero();
        assertThat(corrected.conceptCorrectedAt()).isNotNull();
        assertThat(corrected.conceptCorrectedAt().getNano() % 1000).isZero();
        var readBack = knowledgeQueries.getCandidate(precedent.clause().id());
        assertThat(corrected.updatedAt()).isEqualTo(readBack.updatedAt());
        assertThat(corrected.conceptCorrectedAt()).isEqualTo(readBack.conceptCorrectedAt());
        assertThat(corrected.conceptCorrectedBy()).isEqualTo(readBack.conceptCorrectedBy());
    }

    @Test
    void correctionRejectsUnknownConceptsAndPublishedCandidatesAcrossTenants() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);

        assertThatThrownBy(() -> reviewService.correctConcept(
                precedent.clause().id(), "NOT_A_CONCEPT"))
                .isInstanceOf(com.rivell.workbench.knowledge.InvalidConceptCorrectionException.class);

        var approved = reviewService.approve(
                reviewService.correctConcept(precedent.clause().id(),
                        CoreConcepts.LIMITATION_OF_LIABILITY.value()).id(), null);
        var publication = publicationService.publish(
                java.util.List.of(approved.id()), java.util.Map.of(),
                "published before tenant probe");

        // tenant isolation: another organization cannot correct the candidate
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
        assertThatThrownBy(() -> reviewService.correctConcept(
                precedent.clause().id(), CoreConcepts.CONFIDENTIALITY_OBLIGATIONS.value()))
                .isInstanceOf(com.rivell.workbench.knowledge.KnowledgeCandidateNotFoundException.class);

        // published knowledge is immutable: no post-publication correction
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId, java.util.Set.of("ADMIN")));
        assertThatThrownBy(() -> reviewService.correctConcept(
                precedent.clause().id(), CoreConcepts.CONFIDENTIALITY_OBLIGATIONS.value()))
                .isInstanceOf(com.rivell.workbench.knowledge.InvalidCandidateReviewStateException.class);
    }

    @Test
    void rejectedCandidatesCannotBeCorrectedAndUnauthorizedCallersAreRefused() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);

        reviewService.reject(precedent.clause().id(), "not acceptable");
        assertThatThrownBy(() -> reviewService.correctConcept(
                precedent.clause().id(), CoreConcepts.LIMITATION_OF_LIABILITY.value()))
                .isInstanceOf(com.rivell.workbench.knowledge.InvalidCandidateReviewStateException.class);

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId, java.util.Set.of("VIEWER")));
        assertThatThrownBy(() -> reviewService.correctConcept(
                precedent.clause().id(), CoreConcepts.LIMITATION_OF_LIABILITY.value()))
                .isInstanceOf(com.rivell.workbench.knowledge.UnauthorizedKnowledgeAccessException.class);
    }

    @Test
    void correctingAnApprovedCandidateDemotesToEditedAndRequiresReApproval() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);

        reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.LIMITATION_OF_LIABILITY.value());
        reviewService.approve(precedent.clause().id(), "approved on the corrected concept");

        // a second correction invalidates the recorded approval: demote to EDITED
        var corrected = reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.CONFIDENTIALITY_OBLIGATIONS.value());
        assertThat(corrected.reviewStatus()).isEqualTo(com.rivell.workbench.knowledge.ReviewStatus.EDITED);
        assertThat(corrected.conceptCorrectedBy()).isNotNull();
        assertThat(corrected.conceptCorrectedAt()).isNotNull();

        var reloaded = knowledgeQueries.getCandidate(precedent.clause().id());
        assertThat(reloaded.reviewStatus()).isEqualTo(com.rivell.workbench.knowledge.ReviewStatus.EDITED);
        assertThat(reloaded.conceptCorrectedBy()).isEqualTo(identityProvider.currentIdentity().userId());
        assertThat(reloaded.conceptCorrectedAt()).isNotNull();
        assertThat(reloaded.effectiveConceptKey()).isEqualTo(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS.value());

        // re-approval consumes the effective concept; publication succeeds
        var approved = reviewService.approve(precedent.clause().id(), "re-approved");
        var publication = publicationService.publish(
                java.util.List.of(approved.id()), java.util.Map.of(), "correction re-approval");
        assertThat(publication.publicationNumber()).isPositive();
        var published = knowledgeQueries.getCandidate(precedent.clause().id());
        assertThat(published.effectiveConceptKey()).isEqualTo(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS.value());
        assertThat(published.legalConceptKey()).isNull();
    }

    @Test
    void correctionRecomputesReviewCategorizationOnTheEffectiveConcept() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var precedent = ingestUnclassifiedPrecedent(organizationId);

        // the machine concept is unclassified with low confidence: the categorizer
        // runs on the effective concept, so a correction must recompute the outcome
        var corrected = reviewService.correctConcept(precedent.clause().id(),
                CoreConcepts.LIMITATION_OF_LIABILITY.value());
        assertThat(corrected.reviewCategory()).isNotNull();
        assertThat(corrected.reviewReasons())
                .contains(com.rivell.workbench.knowledge.ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
        assertThat(corrected.conflictSignals()).isEmpty();

        // bulk approval must not shortcut the recomputed (non-ROUTINE) category
        var bulk = reviewService.bulkApprove(java.util.List.of(precedent.clause().id()));
        assertThat(bulk.approvedIds()).isEmpty();
        assertThat(bulk.skipped()).containsKey(precedent.clause().id());
    }
}
