package com.rivell.workbench.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

/**
 * M10 deterministic performance baseline.
 *
 * <p>Measures the real product flow on real PostgreSQL with deterministic scripted providers and
 * records wall time plus JDBC statement counts per stage. Network-free, so it runs in CI and its
 * numbers are comparable between runs.
 *
 * <p>This is <em>not</em> a benchmark and proves no absolute latency claim. It exists to make query
 * shape and round-trip cost visible and to fail loudly if a query count regresses by an order of
 * magnitude, which is what an N+1 regression looks like. Live-provider latency and cost are a
 * separate, manually run, non-gating measurement — see the M10 eval provider-strategy matrix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({GoldenEndToEndIT.GoldenProviderConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class,
        PerformanceBaselineIT.CountingConfiguration.class})
class PerformanceBaselineIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static final String REPORT = "target/eval/performance-baseline.json";

    /**
     * Observed counts on the golden MSA fixture are 62 / 198 / 11 / 22. These ceilings sit near 3-4x
     * that, so normal fixture variation and environment noise pass while an N+1-style regression
     * (an order of magnitude) fails CI. They are not latency claims. A stage that legitimately needs
     * more must raise its ceiling here with a written reason, never silently.
     */
    private static final int CEILING_INGESTION_QUERIES = 200;
    private static final int CEILING_ANALYSIS_QUERIES = 800;
    private static final int CEILING_HUMAN_READ_QUERIES = 80;
    private static final int CEILING_REDLINE_QUERIES = 120;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    private static final String STORAGE_ROOT =
            "target/test-documents/perf/" + UUID.randomUUID();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root", () -> STORAGE_ROOT);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    StatementCounter counter;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void recordsPerStageCostOfTheCompleteFlow() throws Exception {
        var stages = new LinkedHashMap<String, Stage>();

        var file = new MockMultipartFile("file", "m5-msa-services.docx", DOCX_MEDIA_TYPE,
                fixtureBytes("m5-msa-services.docx"));
        var upload = stage("contract_upload", () -> {
            var result = mockMvc.perform(multipart("/api/contracts").file(file))
                    .andExpect(status().isOk())
                    .andReturn();
            return data(result).get("contractId").asText();
        });
        stages.put("contract_upload", upload);
        var contractId = UUID.fromString(upload.value());

        var structure = stage("contract_structure_read", () -> {
            var result = mockMvc.perform(get(
                            "/api/contracts/{id}/versions/{n}/structure", contractId, 1))
                    .andExpect(status().isOk())
                    .andReturn();
            return String.valueOf(data(result).get("nodeCount").asInt());
        });
        stages.put("contract_structure_read", structure);

        var enrichment = stage("contract_semantic_enrichment", () -> {
            mockMvc.perform(post("/api/contracts/{id}/versions/{n}/enrichment", contractId, 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"contractType": "MSA", "ourSideRole": "CUSTOMER",
                                     "paperSource": "THIRD_PARTY", "jurisdiction": "US",
                                     "contractPackKey": "MSA_PACK"}"""))
                    .andExpect(status().isOk());
            return "READY";
        });
        stages.put("contract_semantic_enrichment", enrichment);

        var publication = stage("knowledge_publication", () -> {
            corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                    M6EvidenceCorpus.msaManifest2026(), null);
            corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                    M6EvidenceCorpus.msaManifest2026(), null);
            return "PUBLISHED";
        });
        stages.put("knowledge_publication", publication);

        var reviewId = new UUID[1];
        var creation = stage("review_creation", () -> {
            var result = mockMvc.perform(post(
                            "/api/contracts/{id}/versions/{n}/reviews", contractId, 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contractPackKey\": \"%s\"}"
                                    .formatted(ReviewPackRegistry.MSA_PACK)))
                    .andExpect(status().isOk())
                    .andReturn();
            reviewId[0] = UUID.fromString(data(result).get("id").asText());
            return String.valueOf(data(result).get("plannedChecks").asInt());
        });
        stages.put("review_creation", creation);

        var analysis = stage("review_analysis", () -> {
            var run = analysisService.analyze(organizationId(), reviewId[0]);
            return run.status().name() + "/run" + run.runNumber();
        });
        stages.put("review_analysis", analysis);
        assertThat(analysis.statements())
                .as("review analysis statement count (N+1 guard)")
                .isLessThanOrEqualTo(CEILING_ANALYSIS_QUERIES);

        var humanRead = stage("human_review_read", () -> {
            var result = mockMvc.perform(get("/api/reviews/{id}/human-review", reviewId[0]))
                    .andExpect(status().isOk())
                    .andReturn();
            return data(result).get("status").asText();
        });
        stages.put("human_review_read", humanRead);
        assertThat(humanRead.statements())
                .as("human review read statement count (N+1 guard)")
                .isLessThanOrEqualTo(CEILING_HUMAN_READ_QUERIES);

        var decisions = stage("human_decisions", () -> {
            var findings = findings(reviewId[0]);
            for (var finding : findings) {
                if ("INFORMATIONAL".equals(finding.get("reviewClassification").asText())) {
                    continue;
                }
                if ("ABSENCE".equals(finding.get("subjectKind").asText())) {
                    mockMvc.perform(put("/api/reviews/{id}/findings/{fid}/decision",
                                    reviewId[0], finding.get("id").asText())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion": 0, "action": "EDIT",
                                     "approvedLanguage": "Inserted approved language."}
                                    """))
                            .andExpect(status().isOk());
                } else {
                    mockMvc.perform(put("/api/reviews/{id}/findings/{fid}/decision",
                                    reviewId[0], finding.get("id").asText())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion": 0, "action": "ACCEPT"}"""))
                            .andExpect(status().isOk());
                }
            }
            return String.valueOf(findings.size());
        });
        stages.put("human_decisions", decisions);

        var finalization = stage("review_finalization", () -> {
            var result = mockMvc.perform(post("/api/reviews/{id}/finalize", reviewId[0]))
                    .andExpect(status().isOk())
                    .andReturn();
            return String.valueOf(data(result).get("changes").size());
        });
        stages.put("review_finalization", finalization);

        var redline = stage("redline_execution", () -> {
            var result = mockMvc.perform(post("/api/reviews/{id}/redline", reviewId[0]))
                    .andExpect(status().isOk())
                    .andReturn();
            return data(result).get("outcome").asText();
        });
        stages.put("redline_execution", redline);
        assertThat(redline.statements())
                .as("redline execution statement count (N+1 guard)")
                .isLessThanOrEqualTo(CEILING_REDLINE_QUERIES);

        var download = stage("redline_download", () -> {
            var bytes = mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId[0]))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            return String.valueOf(bytes.length);
        });
        stages.put("redline_download", download);

        assertThat(stages.get("contract_upload").statements())
                .as("contract upload statement count (N+1 guard)")
                .isLessThanOrEqualTo(CEILING_INGESTION_QUERIES);

        var report = new LinkedHashMap<String, Object>();
        report.put("environment", environment());
        report.put("note", "deterministic scripted providers; no network; not a latency claim");
        var stageRows = new ArrayList<Map<String, Object>>();
        for (var entry : stages.entrySet()) {
            stageRows.add(entry.getValue().asRow());
        }
        report.put("stages", stageRows);
        report.put("totalStatements", stages.values().stream()
                .mapToInt(Stage::statements).sum());
        report.put("totalMillis", stages.values().stream()
                .mapToLong(Stage::millis).sum());

        var target = Path.of(REPORT);
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(report), UTF_8);
    }

    private Map<String, Object> environment() {
        var env = new LinkedHashMap<String, Object>();
        env.put("java", System.getProperty("java.version"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("storageRoot", STORAGE_ROOT);
        return env;
    }

    private record Stage(String name, long millis, int statements, String value) {

        Map<String, Object> asRow() {
            var row = new LinkedHashMap<String, Object>();
            row.put("stage", name);
            row.put("millis", millis);
            row.put("jdbcStatements", statements);
            row.put("result", value);
            return row;
        }
    }

    private Stage stage(String name, ThrowingSupplier body) throws Exception {
        counter.reset();
        var startedAt = System.nanoTime();
        var value = body.get();
        var millis = (System.nanoTime() - startedAt) / 1_000_000;
        return new Stage(name, millis, counter.count(), value);
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private List<JsonNode> findings(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        var items = new ArrayList<JsonNode>();
        data(result).get("items").forEach(items::add);
        return items;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private byte[] fixtureBytes(String fixture) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(fixture));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** Counts JDBC statements executed by the application during a stage. */
    static final class StatementCounter {

        private final AtomicInteger statements = new AtomicInteger();

        void reset() {
            statements.set(0);
        }

        int count() {
            return statements.get();
        }

        void record() {
            statements.incrementAndGet();
        }
    }

    /**
     * Wraps the real DataSource so every statement and prepared-statement execution is counted.
     * A proxy rather than a new dependency: it counts what the application actually sends.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class CountingConfiguration {

        @Bean
        StatementCounter statementCounter() {
            return new StatementCounter();
        }

        /**
         * Wraps every DataSource bean after initialization. A post-processor rather than a second
         * DataSource bean: declaring one would make the proxy depend on the DataSource it replaces
         * and form a cycle with Flyway.
         */
        @Bean
        static BeanPostProcessor countingDataSource(StatementCounter counter) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return Proxy.newProxyInstance(
                                DataSource.class.getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                new CountingHandler(dataSource, counter));
                    }
                    return bean;
                }
            };
        }

        private record CountingHandler(DataSource delegate, StatementCounter counter)
                implements InvocationHandler {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                switch (method.getName()) {
                    case "getConnection" -> {
                        return wrap(method.invoke(delegate, args));
                    }
                    case "unwrap" -> {
                        return method.invoke(delegate, args);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return "CountingDataSource[" + delegate + "]";
                    }
                    default -> {
                        return method.invoke(delegate, args);
                    }
                }
            }

            private Object wrap(Object connection) throws Throwable {
                if (!(connection instanceof Connection real)) {
                    return connection;
                }
                return Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                var name = method.getName();
                                if (name.equals("prepareStatement")
                                        || name.equals("prepareCall")
                                        || name.equals("createStatement")) {
                                    counter.record();
                                }
                                if (name.equals("equals")) {
                                    return proxy == args[0];
                                }
                                if (name.equals("hashCode")) {
                                    return System.identityHashCode(proxy);
                                }
                                if (name.equals("toString")) {
                                    return "CountingConnection[" + real + "]";
                                }
                                if (name.equals("unwrap") || name.equals("isWrapperFor")) {
                                    return method.invoke(real, args);
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }
                        });
            }
        }
    }

    // Referenced to keep the import of Driver/PrintWriter/Logger/ SQLException meaningful if the
    // proxy strategy is extended; removed if not.
}
