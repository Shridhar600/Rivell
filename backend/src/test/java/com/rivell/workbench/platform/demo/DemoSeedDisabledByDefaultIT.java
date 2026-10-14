package com.rivell.workbench.platform.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The demo seed must be inert unless a developer asks for it.
 *
 * <p>This is the default configuration: no property is set, so neither the seeder nor the
 * scripted providers exist, and no demo row is written.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DemoSeedDisabledByDefaultIT {

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
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void noSeederAndNoScriptedProvidersExistWithoutTheProperty() {
        assertThat(context.getBeanNamesForType(HomeDemoSeeder.class))
                .as("the seeder must not exist unless demo seeding is explicitly enabled")
                .isEmpty();
        assertThat(context.getBeanNamesForType(DemoScriptedDecisionProvider.class))
                .as("the scripted provider must be unreachable in a normal deployment")
                .isEmpty();
        assertThat(context.getBeanNamesForType(DemoScriptedGenerativeProvider.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoScenarioSeeder.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoAnalysisFaultPolicy.class))
                .as("the injected-failure seam must not exist in a normal deployment")
                .isEmpty();
        assertThat(context.getBeanNamesForType(DemoFaultingReviewRepository.class)).isEmpty();
        assertThat(context.containsBean("demoFaultingReviewRepository"))
                .as("the review repository must not be decorated outside the demo")
                .isFalse();
    }

    @Test
    void nothingIsSeededAndTheApplicationStillServesItsReads() throws Exception {
        assertThat(jdbcClient.sql("select count(*) from contract")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from knowledge_artifact")
                .query(Integer.class).single()).isZero();

        // A normal startup is fully functional; the seed is additive, not a prerequisite.
        mockMvc.perform(get("/api/contracts")).andExpect(status().isOk());
        mockMvc.perform(get("/api/reviews")).andExpect(status().isOk());
        mockMvc.perform(get("/api/knowledge/artifacts")).andExpect(status().isOk());
    }
}
