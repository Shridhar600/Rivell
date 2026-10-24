package com.rivell.workbench.platform.jobs;

import java.util.List;

import org.jobrunr.server.BackgroundJobServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Registers {@link ReviewAnalysisRetryExhaustedFilter} on the JobRunr background server.
 *
 * <p>The 8.8.1 Spring Boot starter's auto-configuration does not collect {@code JobFilter} beans:
 * {@code JobRunrAutoConfiguration#backgroundJobServer} builds the server and registers only the
 * starter's own {@code RetryFilter}. What it does expose is that server as a bean
 * ({@code @ConditionalOnMissingBean}, {@code jobrunr.background-job-server.enabled=true}), and
 * {@code BackgroundJobServer#setJobFilters(List)} appends to its filter list. The server itself
 * starts on {@code ApplicationReadyEvent} in {@code JobRunrStarter}, after singletons are
 * initialised, so this callback registers the filter before the first job can run.
 */
@Component
class ReviewAnalysisJobFilterRegistrar implements SmartInitializingSingleton {

    private final ObjectProvider<BackgroundJobServer> backgroundJobServer;
    private final ObjectProvider<ReviewAnalysisRetryExhaustedFilter> retryExhaustedFilter;

    ReviewAnalysisJobFilterRegistrar(
            ObjectProvider<BackgroundJobServer> backgroundJobServer,
            ObjectProvider<ReviewAnalysisRetryExhaustedFilter> retryExhaustedFilter) {
        this.backgroundJobServer = backgroundJobServer;
        this.retryExhaustedFilter = retryExhaustedFilter;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var server = backgroundJobServer.getIfAvailable();
        if (server == null) {
            return;
        }
        server.setJobFilters(List.of(retryExhaustedFilter.getObject()));
    }
}
