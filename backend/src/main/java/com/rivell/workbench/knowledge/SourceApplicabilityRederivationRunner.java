package com.rivell.workbench.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the one-time source-applicability re-derivation after the schema is ready. The service
 * checks the completion marker first, so later starts only read that marker. Choosing a runner
 * keeps the work on the same Spring-managed code path as the compiler, including the payload
 * parser and the derivation method, instead of copying either into SQL.
 */
@Component
@Order(100)
public class SourceApplicabilityRederivationRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(SourceApplicabilityRederivationRunner.class);

    private final SourceApplicabilityRederivationService rederivation;

    SourceApplicabilityRederivationRunner(SourceApplicabilityRederivationService rederivation) {
        this.rederivation = rederivation;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var result = rederivation.rederiveOnce();
            if (result.examined() > 0) {
                log.info("Source contract type re-derivation: examined={} updated={}",
                        result.examined(), result.updated());
            }
        } catch (RuntimeException e) {
            // The transaction rolled back, including the completion marker, so the next start
            // retries. A data backfill must not keep the product from starting in the meantime.
            log.error("Source contract type re-derivation failed; it will be retried on the next "
                    + "start", e);
        }
    }
}
