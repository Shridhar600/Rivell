package com.rivell.workbench.workflow;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.workflow.api.WorkflowApiService;

@Configuration(proxyBeanMethods = false)
class WorkflowConfiguration {

    @Bean
    HumanDecisionService humanDecisionService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            HumanDecisionRepository decisionRepository,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ContractQueryService contractQueryService) {
        return new HumanDecisionService(identityProvider, authorizationService,
                reviewRepository, analysisRepository, findingRepository,
                decisionRepository, changeSetRepository, transactionTemplate, clock,
                contractQueryService);
    }

    @Bean
    UserFindingService userFindingService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        return new UserFindingService(identityProvider, authorizationService, reviewRepository,
                analysisRepository, findingRepository, changeSetRepository, transactionTemplate,
                clock);
    }

    @Bean
    HumanReviewService humanReviewService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            HumanDecisionRepository decisionRepository,
            ApprovedChangeSetRepository changeSetRepository,
            ContractQueryService contractQueryService) {
        return new HumanReviewService(reviewRepository, analysisRepository,
                findingRepository, decisionRepository, changeSetRepository,
                contractQueryService);
    }

    @Bean
    ReviewFinalizationService reviewFinalizationService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            HumanReviewService humanReviewService,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        return new ReviewFinalizationService(identityProvider, authorizationService,
                reviewRepository, humanReviewService, changeSetRepository,
                transactionTemplate, clock);
    }

    @Bean
    WorkflowApiService workflowApiService(
            HumanDecisionService humanDecisionService,
            UserFindingService userFindingService,
            HumanReviewService humanReviewService,
            ReviewFinalizationService reviewFinalizationService,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new WorkflowApiService(humanDecisionService, userFindingService,
                humanReviewService, reviewFinalizationService, identityProvider,
                authorizationService);
    }
}
