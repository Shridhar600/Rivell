package com.rivell.workbench.platform.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ReviewAnalysisJobFilterRegistrarTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void registersTheRetryExhaustedFilterOnTheStartersBackgroundServer() {
        var server = mock(BackgroundJobServer.class);
        var filter = mock(ReviewAnalysisRetryExhaustedFilter.class);
        @SuppressWarnings("unchecked")
        var filters = mock(ObjectProvider.class);
        when(filters.getObject()).thenReturn(filter);

        new ReviewAnalysisJobFilterRegistrar(provider(server), filters)
                .afterSingletonsInstantiated();

        verify(server).setJobFilters(List.of(filter));
    }

    @Test
    void doesNothingWhenTheBackgroundServerIsDisabled() {
        @SuppressWarnings("unchecked")
        var filters = mock(ObjectProvider.class);

        new ReviewAnalysisJobFilterRegistrar(provider(null), filters)
                .afterSingletonsInstantiated();

        verify(filters, never()).getObject();
    }
}
