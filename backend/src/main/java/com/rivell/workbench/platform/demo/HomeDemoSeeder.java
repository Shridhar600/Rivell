package com.rivell.workbench.platform.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the demo scenario seed when {@code rivell.demo.seed-enabled=true}. The runner is the
 * only startup entry point; all states are produced by {@link DemoScenarioSeeder} through
 * the real application services.
 */
@Component
@Order(0)
@ConditionalOnProperty(prefix = "rivell.demo", name = "seed-enabled", havingValue = "true")
public class HomeDemoSeeder implements ApplicationRunner {

    private final DemoScenarioSeeder seeder;

    HomeDemoSeeder(DemoScenarioSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seeder.seed();
    }
}
