package com.rivell.workbench.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the one-time document-title re-derivation after the schema is ready. The service checks
 * the completion marker first, so later starts only read that marker. Choosing a runner keeps
 * the work on the same Spring-managed code path as the compiler, including the node model and
 * the title derivation, instead of copying either into SQL.
 */
@Component
@Order(101)
public class KnowledgeTitleRederivationRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeTitleRederivationRunner.class);

    private final KnowledgeTitleRederivationService rederivation;

    KnowledgeTitleRederivationRunner(KnowledgeTitleRederivationService rederivation) {
        this.rederivation = rederivation;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var result = rederivation.rederiveOnce();
            if (result.examined() > 0) {
                log.info("Document title re-derivation: examined={} updated={}",
                        result.examined(), result.updated());
            }
        } catch (RuntimeException e) {
            // The transaction rolled back, including the completion marker, so the next start
            // retries. A data backfill must not keep the product from starting in the meantime.
            log.error("Document title re-derivation failed; it will be retried on the next start",
                    e);
        }
    }
}
