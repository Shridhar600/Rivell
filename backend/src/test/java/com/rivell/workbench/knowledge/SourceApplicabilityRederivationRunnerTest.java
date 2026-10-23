package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SourceApplicabilityRederivationRunnerTest {

    @Test
    void aFailedBackfillDoesNotStopTheApplicationFromStarting() {
        var service = mock(SourceApplicabilityRederivationService.class);
        when(service.rederiveOnce()).thenThrow(new IllegalStateException("bad legacy payload"));

        var runner = new SourceApplicabilityRederivationRunner(service);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }
}
