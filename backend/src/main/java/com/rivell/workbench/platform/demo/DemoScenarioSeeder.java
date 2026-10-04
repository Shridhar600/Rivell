package com.rivell.workbench.platform.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.RedlineExecutionService;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;
import com.rivell.workbench.workflow.HumanDecisionRepository;
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.HumanReviewService;
import com.rivell.workbench.workflow.ReviewFinalizationService;

/**
 * Orchestrates the deterministic demo scenario catalog. Everything is produced by the real
 * services the product uses; the only demo-only seam is the R10 analysis failure, which is
 * thrown from a real collaborator and persisted by the real orchestration.
 */
@Component
@ConditionalOnProperty(prefix = "rivell.demo", name = "seed-enabled", havingValue = "true")
final class DemoScenarioSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioSeeder.class);

    private final DemoContractSeeder contracts;
    private final DemoKnowledgeSeeder knowledge;
    private final DemoReviewSeeder reviews;
    private final KnowledgeQueryService knowledgeQueries;

    DemoScenarioSeeder(
            ContractIngestionService contractIngestion,
            ContractEnrichmentService enrichment,
            ContractQueryService contractQueries,
            IdentityProvider identityProvider,
            KnowledgeIngestionService knowledgeIngestion,
            KnowledgeQueryService knowledgeQueries,
            CandidateReviewService candidateReview,
            KnowledgePublicationService publications,
            ReviewService reviewService,
            ReviewAnalysisService analysis,
            HumanDecisionService decisions,
            HumanDecisionRepository decisionRepository,
            HumanReviewService humanReview,
            ReviewFinalizationService finalization,
            ApprovedChangeSetRepository changeSets,
            RedlineExecutionService redline,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            ReviewRepository reviewRepository,
            DemoAnalysisFaultPolicy faultPolicy) {
        this.contracts = new DemoContractSeeder(contractIngestion, enrichment, contractQueries,
                identityProvider);
        this.knowledge = new DemoKnowledgeSeeder(knowledgeIngestion, knowledgeQueries,
                candidateReview, publications);
        this.reviews = new DemoReviewSeeder(reviewService, analysis, enrichment, decisions,
                decisionRepository, humanReview, finalization, changeSets, redline,
                analysisRepository, findingRepository, reviewRepository, faultPolicy);
        this.knowledgeQueries = knowledgeQueries;
    }

    DemoSeedSummary seed() {
        var started = System.currentTimeMillis();
        var seededContracts = contracts.seed();
        knowledge.publishBootstrap();
        knowledge.seedDraftAndFailure();
        var seededReviews = reviews.seed(seededContracts);
        knowledge.publishPlaybookV2();

        var summary = new DemoSeedSummary(
                seededContracts.size(),
                seededReviews.reviews(),
                seededReviews.findings(),
                knowledgeQueries.listArtifacts(null).size(),
                knowledgeQueries.listPublications(null).size(),
                knowledge.escalationTriggerPublished(),
                System.currentTimeMillis() - started);
        logCatalog();
        log.info("Rivell demo scenarios: {} contracts, {} reviews, {} findings, "
                        + "{} knowledge sources, {} publications, escalation trigger published={}, "
                        + "seed {} ms",
                summary.contracts(), summary.reviews(), summary.findings(),
                summary.knowledgeSources(), summary.publications(),
                summary.escalationTriggerPublished(), summary.runtimeMillis());
        return summary;
    }

    private void logCatalog() {
        for (var scenario : DemoScenarioCatalog.all()) {
            log.info("Rivell demo scenario {} [{}] {} - {}",
                    scenario.key(), scenario.kind(), scenario.displayName(),
                    scenario.intendedState());
        }
    }
}
