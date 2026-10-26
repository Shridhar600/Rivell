package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.rivell.workbench.contracts.api.TenantEnrichmentFailureReason;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.review.LiabilityReviewIT;

/**
 * The WHOLE tenant-facing semantics response is inspected, not one field.
 *
 * <p>The first M10A.1 attempt tested {@code failureReason} and stopped there. That is the field the
 * fix was written against, so it is the only field the fix was checked on, and a disclosure through
 * any other route — {@code quality}, {@code observations}, a warning, a nested structure, a new
 * field added later — was invisible to it. This test walks the entire response document: every key
 * and every string value, at every depth, for both a FAILED version and a provider-degraded one.
 *
 * <p>Two different oracles, because they answer two different questions:
 *
 * <ul>
 *   <li>The <b>suppressed operator taxonomy</b> is derived from the production operator enum minus
 *       the production tenant vocabulary — the reasons whose own names the projection deliberately
 *       collapses. No string anywhere in the response may contain one. Deriving it from the two
 *       vocabularies rather than from the mapping keeps the oracle independent of the projection
 *       logic under test.
 *   <li>The <b>secret denylist</b> is a short hand-written list of substrings that no legitimate
 *       semantics field may ever contain. A denylist is legitimately hand-written; a taxonomy is
 *       not, and the earlier hand-written-snapshot defect in this same workstream is why.
 * </ul>
 *
 * <p>Degradation is driven by a fake provider, not a live one: normal CI is network-free, and a
 * gated live skip would guard nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LiabilityReviewIT.MutableIdentityConfiguration.class)
class EnrichmentTenantDisclosureIT {

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

    @Autowired MockMvc mockMvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired LiabilityReviewIT.MutableIdentity identityProvider;
    @Autowired ContractIngestionService contractIngestionService;
    @Autowired ContractEnrichmentService enrichmentService;
    @Autowired JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @AfterEach
    void resetProvider() {
        DegradingProvider.DEGRADED.remove();
        RoutingPolicy.NO_PROVIDER.remove();
    }

    /**
     * Provider attribution that is permitted while the disclosure questions in this area stay open.
     *
     * <p>These two tokens are the only provider-attributed content permitted anywhere in the tenant
     * response, and the allow-list is deliberate rather than a test that pins them: a pinning test
     * would fail when the Director rules, and would present that ruling as a defect. Here, ruling
     * either way changes one line, and any <i>new</i> provider-attributed token fails immediately.
     */
    private static final Set<String> PERMITTED_PROVIDER_ATTRIBUTION =
            Set.of("PROVIDER_DEGRADATION", "providerDegraded");

    /**
     * Never legitimately part of a semantics response. A denylist is correctly hand-written, and it
     * is deliberately narrow: this response carries contract clause text, so a bare word like
     * "secret" or "credential" fires on real legal prose ("third-party claims alleging ... trade
     * secret") and turns the check into a false positive. Only markers that cannot occur in a
     * contract are listed. That narrowness is a correctness property, not laziness — an earlier
     * draft of this list failed on the MSA fixture's own indemnification clause.
     */
    private static final List<String> SECRET_DENYLIST = List.of(
            "api key", "api_key", "apikey", "x-api-key", "authorization:", "bearer ",
            "password", "sk-", "private key", "-----begin");

    /**
     * The FAILED side of the walk, and an honest statement of how much it covers.
     *
     * <p>Measured: this fixture yields 18 field names and 9 string values, because
     * {@code failingVersion()} deletes the version's document nodes and the response therefore
     * carries no clauses, parties, defined terms or regions at all. The degraded fixture yields 328
     * names and 225 strings including ten clauses. So the free-text and nested-structure coverage
     * comes from the degraded fixture; this one covers the FAILED shape, which is where
     * {@code failureReason} and the unconditional quality note appear. Both are needed and neither
     * substitutes for the other.
     */
    @Test
    void aFailedResponseCarriesNoSuppressedOperatorReasonAnywhereInTheDocument() throws Exception {
        var contractId = failingVersion();

        var data = tenantSemantics(contractId);

        // Presence, asserted per fixture and from what the state actually is. The response is
        // NON_NULL, so an absent field would otherwise satisfy the walk below vacuously.
        //
        // `failureReason` is present exactly when the version FAILED. `observations` is ABSENT here,
        // and that is a fact about the state rather than a gap in coverage: enrichment threw before
        // it produced an observations record, so there is no execution telemetry to report.
        //
        // `quality` is NOT absent, and an earlier draft of this comment claimed it was. Every FAILED
        // enrichment is recorded with a PROVIDER_DEGRADATION quality note — `persistFailed` writes it
        // unconditionally, for every classified reason, including UNSUPPORTED_SOURCE where the
        // document is the cause. So this fixture DOES carry provider attribution through `quality`,
        // and the allow-list check below is applied to it for that reason. Whether that write is
        // correct is a separate open question, escalated rather than fixed here.
        assertThat(data.path("enrichmentStatus").asText()).isEqualTo("FAILED");
        assertThat(data.has("failureReason")).isTrue();
        assertThat(data.path("quality").isArray()).isTrue();
        assertThat(data.has("observations"))
                .as("a version that failed before analysis recorded no observations")
                .isFalse();

        assertNoSuppressedOperatorReason(data, "a FAILED response");
        assertNoSecrets(data, "a FAILED response");
        assertTenantCodesAreClosedSets(data, "a FAILED response");

        // Applied here too, not only on the degraded fixture. This response carries provider
        // attribution through `quality` on the FAILED path, so leaving the check to the degraded
        // test would permit the token accidentally rather than knowingly.
        assertThat(providerAttributedTokensIn(data))
                .as("provider attribution on the FAILED path is limited to the escalated allow-list")
                .isSubsetOf(PERMITTED_PROVIDER_ATTRIBUTION)
                .isNotEmpty();
    }

    @Test
    void aProviderDegradedResponseCarriesNoSuppressedOperatorReasonAnywhereInTheDocument()
            throws Exception {
        var contractId = ingest();
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, null);

        // Every bounded semantic judgment fails inside the per-question guard, so the run degrades
        // honestly instead of failing. That is the state in which provider state is otherwise most
        // likely to surface, and it is the case the field-only test never reached.
        DegradingProvider.DEGRADED.set(Boolean.TRUE);
        enrichmentService.enrich(contractId, 1, profile);

        var data = tenantSemantics(contractId);

        // Presence, scoped to this fixture: a degraded version is READY, so `failureReason` is
        // absent by design and asserting it present would be asserting a contradiction.
        assertThat(data.path("enrichmentStatus").asText()).isEqualTo("READY");
        assertThat(data.has("failureReason")).isFalse();
        assertThat(data.path("observations").path("providerDegraded").asBoolean()).isTrue();
        assertThat(data.path("quality").isArray()).isTrue();
        // The fixture must actually be degraded, or the walk below is proving nothing.
        assertThat(data.path("quality").toString())
                .as("this fixture must carry the degradation note it exists to test")
                .contains("PROVIDER_DEGRADATION");

        assertNoSuppressedOperatorReason(data, "a provider-degraded response");
        assertNoSecrets(data, "a provider-degraded response");
        assertTenantCodesAreClosedSets(data, "a provider-degraded response");

        // The only provider-attributed content anywhere is the two allowed tokens, and nothing
        // else in the document mentions a provider at all.
        assertThat(providerAttributedTokensIn(data))
                .as("provider attribution is limited to the escalated allow-list")
                .isSubsetOf(PERMITTED_PROVIDER_ATTRIBUTION)
                .isNotEmpty();
    }

    /**
     * Why the other two fixtures cannot produce a suppressed operator reason — and what would have
     * to change for one to appear.
     *
     * <p>The obvious third fixture would be a FAILED version whose reason is
     * {@code NO_PROVIDER_CONFIGURED}, so the walk could catch a re-exposed provider reason on the
     * wire. That state is not reachable. {@code NoProviderConfiguredException} extends
     * {@code SemanticDecisionException}, and every bounded-judgment call site in
     * {@code ContractSemanticEnricher} catches that type per region, per party, per clause pair and
     * per fact extraction. A provider failure — including the most alarming one, no usable provider
     * at all — therefore degrades the run and leaves it READY. {@code PROVIDER_PROTOCOL} is caught
     * the same way, because the throwing helper is invoked inside the same guarded block.
     *
     * <p>So the provider codes in {@code EnrichmentFailureReason} are defensive depth for a failure
     * mode the current enricher prevents, and no reachable tenant state carries one. The honest
     * consequence is a coverage limit, and this test exists to make it visible: if the per-question
     * guard is ever removed or narrowed, this fails, which is the signal that the whole-document
     * walk has gained a state it can now check and that the mapping test is no longer the only
     * thing standing between an operator reason and the wire.
     *
     * <p>Until then, suppression of the provider reasons is covered at the mapping boundary by
     * {@code EnrichmentFailureIT#noRecordedEnrichmentReasonRevealsProviderStateToTheTenant}, which
     * asserts all seven reasons without needing a fixture per reason. The whole-document walk
     * covers the routes that are reachable today: quality, observations, nested structures, field
     * names and free text.
     */
    @Test
    void aMissingProviderDegradesTheRunRatherThanFailingItSoNoProviderReasonReachesTheWire() {
        var contractId = ingest();
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, null);

        // Route every bounded judgment to a provider id that is not registered. The real
        // SemanticDecisionService raises NoProviderConfiguredException, which the enricher handles
        // inside its per-question guard.
        RoutingPolicy.NO_PROVIDER.set(Boolean.TRUE);
        enrichmentService.enrich(contractId, 1, profile);

        var semantics = enrichmentService.getSemantics(contractId, 1);

        assertThat(semantics.enrichmentStatus())
                .as("a missing provider degrades the analysis; it does not fail the version")
                .isEqualTo(EnrichmentStatus.READY);
        assertThat(semantics.failureReason())
                .as("which is why no provider reason is ever recorded to project")
                .isNull();
        assertThat(semantics.quality().notes())
                .as("the degradation is still reported, as analysis-health information")
                .contains(ReviewQualityNote.PROVIDER_DEGRADATION);
    }
/**
     * The regression guard for the projection itself.
     *
     * <p>A mutation check proved this necessary rather than optional. Replacing the controller's
     * projection with the raw stored reason — a one-line change that puts every operator diagnosis
     * straight back on the wire — left the whole suite green, because no state the current enricher
     * can produce carries a provider reason (see the degradation test above). A test that only ever
     * exercises reachable states cannot guard a mapping whose interesting inputs are unreachable.
     *
     * <p>So the row is written directly, for each of the five provider reasons. That is deliberate
     * and it is the honest boundary of the guard: the column is TEXT and its CHECK enforces only
     * that FAILED carries a reason, so these rows are ones the schema permits and the read path must
     * handle. If the enricher later grows a failure mode that produces one of them, this test is
     * already waiting. What it does not prove is that such a failure mode exists — it proves the
     * wire stays clean if it does.
     */
    @Test
    void aStoredProviderReasonIsCollapsedBeforeItReachesTheWire() throws Exception {
        var contractId = failingVersion();
        var suppressed = suppressedOperatorReasons();
        assertThat(suppressed)
                .as("the suppressed set must not be empty, or this proves nothing")
                .isNotEmpty();

        for (var reason : suppressed) {
            jdbcClient.sql("""
                    update contract_semantics
                    set enrichment_failure_reason = :reason
                    where contract_version_id = (
                        select id from contract_version
                        where contract_id = :contractId and version_number = 1)
                    """)
                    .param("reason", reason)
                    .param("contractId", contractId)
                    .update();

            var data = tenantSemantics(contractId);

            assertThat(data.path("failureReason").asText())
                    .as("stored %s must not be the value a tenant sees", reason)
                    .isNotEqualTo(reason);
            assertThat(data.path("failureReason").asText())
                    .as("stored %s must collapse to the tenant's service class", reason)
                    .isEqualTo("SERVICE_UNAVAILABLE");
            assertNoSuppressedOperatorReason(data, "a response stored as " + reason);
            assertNoSecrets(data, "a response stored as " + reason);
        }
    }

    // ---------------------------------------------------------------- assertions

    private void assertNoSuppressedOperatorReason(JsonNode data, String what) {
        var suppressed = suppressedOperatorReasons();
        assertThat(suppressed)
                .as("the suppressed set must be derived, not empty, or the walk proves nothing")
                .isNotEmpty();

        for (var token : suppressed) {
            assertThat(stringsIn(data))
                    .as("%s must not contain the suppressed operator reason %s anywhere",
                            what, token)
                    .noneMatch(value -> value.toUpperCase(Locale.ROOT).contains(token));
        }
        for (var key : keysIn(data)) {
            var upper = key.toUpperCase(Locale.ROOT);
            for (var token : suppressed) {
                assertThat(upper.contains(token))
                        .as("%s must not name the suppressed operator reason %s in a field name",
                                what, token)
                        .isFalse();
            }
        }
    }

    private void assertNoSecrets(JsonNode data, String what) {
        for (var value : stringsIn(data)) {
            var lower = value.toLowerCase(Locale.ROOT);
            for (var denied : SECRET_DENYLIST) {
                assertThat(lower)
                        .as("%s must not contain %s in any value", what, denied)
                        .doesNotContain(denied);
            }
        }
        for (var key : keysIn(data)) {
            var lower = key.toLowerCase(Locale.ROOT);
            for (var denied : SECRET_DENYLIST) {
                assertThat(lower)
                        .as("%s must not name %s in a field name", what, denied)
                        .doesNotContain(denied);
            }
        }
    }

    /**
     * Per-field closed-set membership, for the fields that actually carry codes. A blanket
     * "every code-shaped string" rule is not usable here: the document also carries concept keys,
     * identifiers and digests, and a heuristic that matched those would be either inert or flaky.
     */
    private void assertTenantCodesAreClosedSets(JsonNode data, String what) {
        var tenantReasons = names(TenantEnrichmentFailureReason.values());
        var reason = data.path("failureReason");
        if (reason.isTextual()) {
            assertThat(tenantReasons)
                    .as("%s: failureReason must be a tenant-facing reason", what)
                    .contains(reason.asText());
        }
        for (var note : data.path("quality")) {
            assertThat(ReviewQualityNoteNames.allowed())
                    .as("%s: quality notes must come from the domain vocabulary", what)
                    .contains(note.asText());
        }
    }

    // ---------------------------------------------------------------- fixtures

    private UUID failingVersion() {
        var contractId = ingest();
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, null);
        // A version whose parsed structure is gone cannot be enriched: a real FAILED state written
        // by the real service, not a hand-edited row.
        jdbcClient.sql("delete from document_node where contract_version_id = "
                        + "(select id from contract_version where contract_id = :contractId"
                        + " and version_number = 1)")
                .param("contractId", contractId)
                .update();
        assertThatThrownBy(() -> enrichmentService.enrich(contractId, 1, profile))
                .isInstanceOf(ContractNotReadableException.class);
        return contractId;
    }

    private JsonNode tenantSemantics(UUID contractId) throws Exception {
        var result = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/semantics", contractId, 1))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private UUID ingest() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(
                    Path.of("src/test/resources/fixtures/m5-msa-services.docx"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            return contractIngestionService.ingest("m5-msa-services.docx",
                    ContractIngestionService.DOCX_CONTENT_TYPE,
                    new ByteArrayInputStream(bytes))
                    .contractId();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- walking

    /** Every string value in the document, at every depth. */
    private static List<String> stringsIn(JsonNode node) {
        var found = new ArrayList<String>();
        collect(node, found, true);
        return found;
    }

    /** Every field name in the document, at every depth. */
    private static List<String> keysIn(JsonNode node) {
        var found = new ArrayList<String>();
        collect(node, found, false);
        return found;
    }

    private static void collect(JsonNode node, List<String> into, boolean values) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if (!values) {
                    into.add(name);
                }
                collect(node.get(name), into, values);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, into, values));
        } else if (values && node.isTextual()) {
            into.add(node.asText());
        }
    }

    /** Tokens in the document that attribute something to a provider. */
    private static Set<String> providerAttributedTokensIn(JsonNode data) {
        var found = new java.util.LinkedHashSet<String>();
        for (var value : stringsIn(data)) {
            if (value.toUpperCase(Locale.ROOT).contains("PROVIDER")) {
                found.add(value);
            }
        }
        for (var key : keysIn(data)) {
            if (key.toUpperCase(Locale.ROOT).contains("PROVIDER")) {
                found.add(key);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- vocabulary

    /**
     * The reasons the tenant projection deliberately suppresses: every operator reason whose own
     * name does not survive into the tenant vocabulary. Derived from both production types, so a
     * newly added operator reason is picked up automatically and a newly widened tenant vocabulary
     * is caught by the separate assertion in {@code noRecordedEnrichmentReasonRevealsProviderStateToTheTenant}.
     */
    private static Set<String> suppressedOperatorReasons() {
        var tenant = names(TenantEnrichmentFailureReason.values());
        var suppressed = new java.util.LinkedHashSet<String>();
        for (var reason : EnrichmentFailureReason.values()) {
            if (!tenant.contains(reason.name())) {
                suppressed.add(reason.name());
            }
        }
        return suppressed;
    }

    private static Set<String> names(Enum<?>[] values) {
        var out = new java.util.LinkedHashSet<String>();
        for (var value : values) {
            out.add(value.name());
        }
        return out;
    }

    /** The domain quality vocabulary, read from production rather than restated. */
    static final class ReviewQualityNoteNames {
        private ReviewQualityNoteNames() {
        }

        static Set<String> allowed() {
            var out = new java.util.LinkedHashSet<String>();
            for (var note : ReviewQualityNote.values()) {
                out.add(note.name());
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- fake provider

    @TestConfiguration(proxyBeanMethods = false)
    static class DisclosureConfiguration {

        @Bean
        @Primary
        SemanticDecisionProvider disclosureProvider() {
            return new DegradingProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.ModelPolicy disclosureModelPolicy() {
            return new RoutingPolicy();
        }
    }

    /**
     * Routes to an unregistered provider id when asked, which is what makes the real
     * no-provider failure reachable without a live provider and without touching configuration.
     */
    static final class RoutingPolicy implements com.rivell.workbench.intelligence.ModelPolicy {

        static final ThreadLocal<Boolean> NO_PROVIDER = new ThreadLocal<>();

        @Override
        public com.rivell.workbench.intelligence.ModelBinding resolve(
                com.rivell.workbench.intelligence.SemanticAsset asset) {
            if (Boolean.TRUE.equals(NO_PROVIDER.get())) {
                return new com.rivell.workbench.intelligence.ModelBinding(
                        "provider-that-is-not-registered", "any-model");
            }
            return new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-model-latest");
        }
    }

    /**
     * Delegates to the M5 scripted provider, or fails every bounded judgment when the current
     * thread has asked for degradation. A thread-local rather than a field: MockMvc and the
     * enrichment service both run on the calling thread, so the toggle cannot leak between tests.
     */
    static final class DegradingProvider implements SemanticDecisionProvider {

        static final ThreadLocal<Boolean> DEGRADED = new ThreadLocal<>();

        private final SemanticDecisionProvider delegate =
                new ContractEnrichmentIT.CueScriptedProvider();

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            if (Boolean.TRUE.equals(DEGRADED.get())) {
                // The controlled failure model the enricher already handles per question, so the
                // run degrades rather than failing. No provider or transport exception is involved.
                throw new SemanticDecisionException("decision unavailable");
            }
            return delegate.execute(request);
        }
    }
}
