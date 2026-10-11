package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * End-to-end policy comparison (design §6a): one Jev call per clause that shares a
 * topic with published positions, one question per position, findings with the
 * code-owned severity, approved drafting for a walk-away, and no call when no
 * position matches.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class PolicyComparisonFlowIT {

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
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private static UUID candidateId(java.util.List<KnowledgeCandidate> candidates, String rawLabel) {
        return candidates.stream()
                .filter(candidate -> rawLabel.equals(candidate.rawLabel()))
                .findFirst().orElseThrow().id();
    }

    @Test
    void oneCallPerMatchedClauseOneQuestionPerPositionAndSeverityByCategory() throws Exception {
        var organizationId = organizationId();
        var playbook = corpusBuilder.publishAll(organizationId,
                M6EvidenceCorpus.Fixture.PC_PLAYBOOK, M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId,
                M6EvidenceCorpus.Fixture.PC_LIBRARY, M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);

        var run = analysisService.analyze(organizationId, review.id());

        // one call per clause that shares a topic with a published position;
        // the unmatched governing-law clause makes no call
        assertThat(scriptedProvider.policyComparisonDecisions()).isEqualTo(4);
        assertThat(scriptedProvider.policyComparisonCalls())
                .extracting(LiabilityReviewIT.ReviewScriptedProvider.ComparisonCall::heading)
                .anyMatch(heading -> heading.contains("Limitation of Liability"))
                .noneMatch(heading -> heading.contains("Governing Law"));

        // the uncapped liability clause matches two positions: one call, two questions
        var uncappedCall = scriptedProvider.policyComparisonCalls().stream()
                .filter(call -> call.heading().contains("Limitation of Liability"))
                .findFirst().orElseThrow();
        assertThat(uncappedCall.questionKeys()).containsExactly("position_1", "position_2");

        var findings = findingRepository.findFindings(organizationId, run.id());
        var walkAway = findings.stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(walkAway.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(walkAway.reviewClassification()).isEqualTo(FindingClassification.BLOCKING);
        assertThat(walkAway.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(walkAway.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.APPROVED_DRAFTING);
        assertThat(walkAway.proposedLanguage()).contains("two times the fees");

        var deviation = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .filter(signal -> signal.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        var payload = (PositionDeviationPayload) deviation.payload();
        assertThat(payload.category()).isEqualTo(PolicyComparisonSpecs.WALK_AWAY);
        assertThat(payload.secondaryTopic()).isFalse();
        assertThat(payload.positionCandidateId()).isEqualTo(candidateId(playbook,
                "Limitation of Liability"));
        assertThat(deviation.decisionSpecId()).isEqualTo(
                PolicyComparisonSpecs.POLICY_COMPARISON_SPEC);
        assertThat(deviation.jevExecutionId()).isNotNull();

        var withinFallback = findings.stream()
                .filter(finding -> finding.issueFamily()
                        == IssueFamily.POSITION_ACCEPTABLE_FALLBACK)
                .findFirst().orElseThrow();
        assertThat(withinFallback.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(withinFallback.reviewClassification())
                .isEqualTo(FindingClassification.INFORMATIONAL);
        assertThat(withinFallback.proposedAction()).isEqualTo(ProposedAction.NO_ACTION);

        assertThat(findings).hasSize(2);

        // every execution records all judged positions, flagged or not
        var withPositions = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .filter(execution -> execution.detail().get("positions").toString()
                        .contains("flagged=true"))
                .findFirst().orElseThrow();
        var judged = (java.util.List<?>) withPositions.detail().get("positions");
        assertThat(judged).hasSize(2);
        assertThat(judged.toString()).contains("flagged=true").contains("flagged=false");
    }

    @Test
    void aClauseNoPositionAddressesIsTriagedAndBecomesAFindingOnlyWhenAdverse() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);
        scriptedProvider.uncoveredProbability = 0.9;

        var run = analysisService.analyze(organizationId, review.id());

        // the governing-law clause matches no position: triaged, never compared
        assertThat(scriptedProvider.uncoveredHeadings)
                .anyMatch(heading -> heading.contains("Governing Law"));
        var notCovered = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.POLICY_UNAVAILABLE)
                .toList();
        assertThat(notCovered).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.observationKind()).isEqualTo(PolicyUnavailablePayload.NOT_COVERED);
            assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
            assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        });
    }

    @Test
    void comparisonProviderFailureIsUncertaintyPerPositionNotSilentPass() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);
        scriptedProvider.failPolicyComparisonDecision = true;

        var run = analysisService.analyze(organizationId, review.id());

        var uncertain = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.DEVIATION_UNCERTAIN)
                .toList();
        assertThat(uncertain).hasSize(8);
        assertThat(uncertain).allSatisfy(signal -> {
            assertThat(signal.issueFamily()).isEqualTo(IssueFamily.POSITION_UNCERTAIN);
            assertThat(((DeviationUncertainPayload) signal.payload()).reason())
                    .isEqualTo("bounded_decision_unavailable");
        });
        assertThat(findingRepository.findFindings(organizationId, run.id()))
                .hasSize(4)
                .extracting(Finding::proposedAction)
                .containsOnly(ProposedAction.ESCALATE_TO_HUMAN);
    }
}
