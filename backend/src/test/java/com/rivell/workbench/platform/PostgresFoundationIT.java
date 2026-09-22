package com.rivell.workbench.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.jobrunr.jobs.states.StateName;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.foundation.JobScheduler;
import com.rivell.workbench.foundation.JobTask;

@SpringBootTest
@Testcontainers
class PostgresFoundationIT {

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
    }

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JobScheduler jobScheduler;

    @Autowired
    StorageProvider storageProvider;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void appliesFlywayMigrationAndEnablesPgvector() {
        assertThat(jdbcClient.sql("select count(*) from flyway_schema_history where version = '1'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from pg_extension where extname = 'vector'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void persistsAJobWithoutStartingAWorker() {
        JobTask probe = () -> { };

        var jobId = jobScheduler.enqueue(probe);

        assertThat(storageProvider.getJobById(jobId.value()).getState()).isEqualTo(StateName.ENQUEUED);
        assertThat(applicationContext.getBeansOfType(BackgroundJobServer.class)).isEmpty();
    }
}
