package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

@SpringBootTest
@Testcontainers
public class ContractEnrichmentIT {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? new ModelBinding("fake", "fake-gen-model")
                    : new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new CueScriptedProvider();
        }

        @Bean
        @Primary
        GenerativeReasoningProvider fakeGenerativeProvider() {
            return new M5FakeGenerativeProvider();
        }
    }

    public static final class M5FakeGenerativeProvider implements GenerativeReasoningProvider {

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            if (!(request.state().payload() instanceof Map<?, ?> payload)
                    || !(payload.get("text") instanceof String text)) {
                throw new ProviderRejectedException("no text");
            }
            var fields = new java.util.LinkedHashMap<String, String>();
            if (request.spec().id().equals(M5ReasoningSpecs.LIABILITY_CAP_SPEC)) {
                var matcher = java.util.regex.Pattern.compile("([a-z]+) times the",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
                if (!matcher.find()) {
                    throw new ProviderProtocolException("no cap statement");
                }
                var word = matcher.group(1).toLowerCase();
                var multiple = switch (word) {
                    case "two" -> "2";
                    case "three" -> "3";
                    case "four" -> "4";
                    default -> throw new ProviderProtocolException("unknown multiple");
                };
                fields.put("liabilityCapMultiple", multiple);
                fields.put("capBasis", text.contains("preceding") ? "PRECEDING_12_MONTH_FEES"
                        : "UNSPECIFIED");
            } else if (request.spec().id().equals(M5ReasoningSpecs.TERMINATION_NOTICE_SPEC)) {
                var matcher = java.util.regex.Pattern.compile("upon ([a-z]+) days",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
                if (!matcher.find()) {
                    throw new ProviderProtocolException("no notice statement");
                }
                var word = matcher.group(1).toLowerCase();
                var days = switch (word) {
                    case "thirty" -> "30";
                    case "sixty" -> "60";
                    case "ninety" -> "90";
                    default -> throw new ProviderProtocolException("unknown period");
                };
                fields.put("noticePeriodDays", days);
            } else {
                throw new ProviderRejectedException("unknown spec " + request.spec().id());
            }
            return new GenerationResult(fields, "fake-gen-v1", new TokenUsage(120, 12));
        }
    }

    /**
     * Deterministic cue-based answers: every judgment follows the source text so the
     * fake behaves like expected labels without inventing fixture-specific magic.
     */
    public static final class CueScriptedProvider implements SemanticDecisionProvider {

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var answers = new java.util.LinkedHashMap<String, SemanticAnswer>();
            var state = request.state().payload();
            for (var entry : request.pack().questions().entrySet()) {
                var question = entry.getValue();
                if (question instanceof ChoiceQuestion choice) {
                    answers.put(entry.getKey(), choiceAnswer(entry.getKey(), choice, state));
                } else {
                    answers.put(entry.getKey(),
                            new YesNoAnswer(cueProbability(entry.getKey(), state)));
                }
            }
            return new ProviderExecutionResult(answers, "fake-resolved-v5", new TokenUsage(220, 9));
        }

        private ChoiceAnswer choiceAnswer(
                String name, ChoiceQuestion choice, Object state) {
            var selected = switch (name) {
                case "primary_concept" -> primaryConceptCue(choice, state);
                case "governing_law" -> stateText(state).contains("England") ? "UK" : "US";
                default -> choice.criteria().keySet().iterator().next();
            };
            var probabilities = new java.util.LinkedHashMap<String, Double>();
            choice.criteria().keySet().forEach(option ->
                    probabilities.put(option, option.equals(selected) ? 0.92 : 0.04));
            return new ChoiceAnswer(selected, 0.92, probabilities);
        }

        private String primaryConceptCue(ChoiceQuestion choice, Object state) {
            var text = stateText(state);
            var heading = stateHeading(state).toLowerCase();
            var body = text.contains("\n")
                    ? text.substring(text.indexOf('\n')).toLowerCase()
                    : "";
            java.util.Optional<String> headingMatch = java.util.Optional.empty();
            java.util.Optional<String> textMatch = java.util.Optional.empty();
            for (var option : choice.criteria().keySet()) {
                var cue = option.toLowerCase().replace('_', ' ');
                if (headingMatch(heading, cue) && headingMatch.isEmpty()) {
                    headingMatch = java.util.Optional.of(option);
                }
                if (bodyMatch(body, cue) && textMatch.isEmpty()) {
                    textMatch = java.util.Optional.of(option);
                }
            }
            if (headingMatch.isPresent()) {
                return headingMatch.get();
            }
            return textMatch.orElse(choice.criteria().keySet().iterator().next());
        }

        private boolean headingMatch(String heading, String cue) {
            return !heading.isBlank()
                    && (heading.contains(cue) || heading.contains(firstWord(cue)));
        }

        private boolean bodyMatch(String body, String cue) {
            return !body.isBlank() && body.contains(cue);
        }

        private String firstWord(String cue) {
            return cue.split(" ")[0];
        }

        private double cueProbability(String questionName, Object state) {
            var text = stateText(state);
            var lower = text == null ? "" : text.toLowerCase();
            return switch (questionName) {
                case "concept_confidentiality" -> lower.contains("confidential") ? 0.95 : 0.05;
                case "concept_term_termination" ->
                        lower.contains("terminat") || lower.contains("term ") ? 0.95 : 0.05;
                case "concept_limitation_of_liability" -> lower.contains("liability") ? 0.95 : 0.05;
                case "concept_indemnification" -> lower.contains("indemnif") ? 0.95 : 0.05;
                case "concept_governing_law" -> lower.contains("govern") ? 0.95 : 0.05;
                case "role_disclosing_party" -> lower.contains("disclosing") ? 0.95 : 0.05;
                case "role_receiving_party" -> lower.contains("receiving") ? 0.95 : 0.05;
                case "role_customer" -> lower.contains("customer") ? 0.95 : 0.05;
                case "role_supplier" -> lower.contains("supplier") ? 0.95 : 0.05;
                case "source_supports" -> 0.95;
                case "rel_depends_on" -> lower.contains("subject to") ? 0.9 : 0.05;
                case "rel_modifies" -> lower.contains("amend") || lower.contains("modify")
                        ? 0.9 : 0.05;
                case "rel_survives" -> lower.contains("survive") ? 0.9 : 0.05;
                case "rel_carves_out" -> lower.contains("carve") || lower.contains("do not apply")
                        ? 0.9 : 0.05;
                default -> 0.05;
            };
        }

        private String stateText(Object state) {
            if (state instanceof Map<?, ?> payload) {
                for (var key : List.of("text", "excerpt")) {
                    if (payload.get(key) instanceof String value) {
                        return value;
                    }
                }
                if (payload.get("source") instanceof Map<?, ?> source
                        && source.get("text") instanceof String sourceText) {
                    return sourceText;
                }
            }
            return "";
        }

        private String stateHeading(Object state) {
            if (state instanceof Map<?, ?> payload && payload.get("heading") instanceof String heading) {
                return heading;
            }
            if (state instanceof Map<?, ?> payload
                    && payload.get("source") instanceof Map<?, ?> source
                    && source.get("heading") instanceof String heading) {
                return heading;
            }
            return "";
        }
    }

    @Autowired
    ContractIngestionService ingestionService;

    @Autowired
    MutableIdentity identityService;

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
        }
    }

    public static final class MutableIdentity implements IdentityProvider {

        private volatile UserIdentity current = new UserIdentity(
                ORG_A, ORG_A, java.util.Set.of("ADMIN"));

        public MutableIdentity() {
        }

        public void switchTo(UUID organizationId) {
            current = new UserIdentity(current.userId(), organizationId,
                    java.util.Set.of("ADMIN"));
        }

        public void reset() {
            current = new UserIdentity(current.userId(), ORG_A, java.util.Set.of("ADMIN"));
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    private static final UUID ORG_A = UUID.randomUUID();

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    ContractQueryService queryService;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void ndaEnrichesIntoTypedSemanticContract() throws Exception {
        var bytes = fixtureBytes("m5-nda-counterparty.docx");
        var ingested = ingestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.NDA));

        assertThat(semantics.enrichmentStatus()).isEqualTo(EnrichmentStatus.READY);

        assertThat(metadataByField(semantics, MetadataField.AGREEMENT_TITLE))
                .isEqualTo("Mutual Non-Disclosure Agreement");
        assertThat(metadataByField(semantics, MetadataField.EFFECTIVE_DATE))
                .isEqualTo("2026-03-15");
        assertThat(metadataByField(semantics, MetadataField.GOVERNING_LAW)).isEqualTo("US");
        assertThat(metadataByField(semantics, MetadataField.CONTRACT_TYPE)).isEqualTo("NDA");

        var roles = semantics.parties().stream()
                .collect(java.util.stream.Collectors.toMap(ContractParty::rawName,
                        ContractParty::effectiveRoles, (a, b) -> a));
        assertThat(roles).containsKeys("Helios Systems Inc.", "Vertex Analytics Ltd.");
        assertThat(roles.get("Helios Systems Inc.")).contains(PartyRole.DISCLOSING_PARTY);
        assertThat(roles.get("Vertex Analytics Ltd.")).contains(PartyRole.RECEIVING_PARTY);

        var terms = semantics.definedTerms().stream()
                .collect(java.util.stream.Collectors.toMap(DefinedTerm::term,
                        term -> term, (a, b) -> a));
        assertThat(terms).containsKeys("Confidential Information", "Representatives");
        assertThat(terms.get("Confidential Information").referenceNodeIds()).isNotEmpty();

        var resolved = semantics.references().stream()
                .filter(reference -> reference.status() == ReferenceStatus.RESOLVED)
                .toList();
        assertThat(resolved).isNotEmpty();

        var conceptLabels = semantics.clauses().stream()
                .filter(clause -> !clause.classification().concepts().isEmpty())
                .flatMap(clause -> clause.classification().concepts().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(conceptLabels).contains(LegalConcept.CONFIDENTIALITY);
        assertThat(conceptLabels).contains(LegalConcept.GOVERNING_LAW);
        assertThat(conceptLabels).contains(LegalConcept.TERM_TERMINATION);

        // signature blocks are structural regions now: the NDA's real clauses all
        // classify, so the only remaining quality note (if any) is honest state
        assertThat(semantics.quality().notes())
                .doesNotContain(ReviewQualityNote.PROVIDER_DEGRADATION,
                        ReviewQualityNote.UNVERIFIED_FACTS,
                        ReviewQualityNote.AMBIGUOUS_PARTY_ROLES);
        assertThat(semantics.observations().decisionExecutionCount()).isPositive();
    }

    /**
     * DW-051: clause order is document order. The clause read had no ORDER BY, so the order a
     * contract's clauses came back in depended on physical row layout: the semantics API listed
     * them in an arbitrary order that could change after any update, and the demo's clause picker
     * chose a different clause from run to run. This asserts the order is the one the document
     * itself has, which is the only order that means anything for a contract.
     */
    @Test
    void clausesAreReadInDocumentOrder() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        var documentOrder = queryService.getStructure(ingested.contractId(), 1).nodes().stream()
                .map(node -> node.id())
                .toList();
        assertThat(documentOrder)
                .as("the fixture must have enough nodes for the ordering to be meaningful")
                .hasSizeGreaterThan(3);

        assertThat(clausePositions(semantics, documentOrder))
                .as("every clause must be anchored to a real document node")
                .doesNotContain(-1);
        assertThat(clausePositions(semantics, documentOrder))
                .as("clauses must be returned in the order their headings appear in the document")
                .isSorted();
        assertThat(semantics.clauses())
                .as("the fixture must actually produce several clauses to order")
                .hasSizeGreaterThanOrEqualTo(3);

        // Asserting document order on a freshly built contract proves nothing: the read is served
        // by the unique index on (semantics_id, heading_node_id), so it returns heading-node id
        // order, which only looks like document order when the ids happen to ascend with the
        // document. They are random UUIDs in production. Exchange the ids of two heading nodes so
        // id order provably contradicts document order while the document itself is untouched,
        // then read again. Only a read that imposes the document order can pass.
        swapHeadingNodeIds(semantics);

        // The document's own reading order is unchanged by the swap, but its node ids are new,
        // so the expected order has to be read again rather than reused.
        var documentOrderAfterSwap = queryService.getStructure(ingested.contractId(), 1).nodes()
                .stream().map(node -> node.id()).toList();
        assertThat(documentOrderAfterSwap)
                .as("exchanging two ids must not disturb the document itself")
                .containsExactlyInAnyOrderElementsOf(documentOrder);

        var reread = queryService.getSemanticsForOrganization(
                        identityService.currentIdentity().organizationId(),
                        ingested.contractId(), 1)
                .orElseThrow();
        assertThat(clausePositions(reread, documentOrderAfterSwap))
                .as("clause order must come from the document, not from heading-node id order")
                .doesNotContain(-1)
                .isSorted();
    }

    private List<Integer> clausePositions(ContractSemantics semantics, List<UUID> documentOrder) {
        return semantics.clauses().stream()
                .map(clause -> documentOrder.indexOf(clause.headingNodeId()))
                .toList();
    }

    /**
     * Exchanges the ids of two clause heading nodes, and repoints the clause rows at them, so the
     * order the (semantics_id, heading_node_id) index yields contradicts the document order. Only
     * childless nodes are used, so the document tree and its reading order stay intact.
     */
    private void swapHeadingNodeIds(ContractSemantics semantics) {
        var clauses = semantics.clauses();
        assertThat(clauses).hasSizeGreaterThanOrEqualTo(2);
        var first = clauses.get(0).headingNodeId();
        var last = clauses.get(clauses.size() - 1).headingNodeId();
        assertThat(first).isNotEqualTo(last);
        for (var nodeId : List.of(first, last)) {
            assertThat(jdbc.sql("select count(*) from document_node where parent_id = :id")
                    .param("id", nodeId)
                    .query(Long.class)
                    .single())
                    .as("only childless nodes can trade ids without disturbing the tree")
                    .isZero();
        }
        jdbc.sql(contractClauseSemanticsSwap).param("to", ZERO).param("from", first).update();
        jdbc.sql(contractClauseSemanticsSwap).param("to", first).param("from", last).update();
        jdbc.sql(contractClauseSemanticsSwap).param("to", last).param("from", ZERO).update();
        jdbc.sql("update document_node set id = :to where id = :from")
                .param("to", ZERO).param("from", first).update();
        jdbc.sql("update document_node set id = :to where id = :from")
                .param("to", first).param("from", last).update();
        jdbc.sql("update document_node set id = :to where id = :from")
                .param("to", last).param("from", ZERO).update();
    }

    /**
     * Three-step swap through a sentinel: (semantics_id, heading_node_id) is unique, so a
     * two-step exchange would transiently duplicate a key.
     */
    private static final String contractClauseSemanticsSwap =
            "update contract_clause_semantics set heading_node_id = :to where heading_node_id = :from";

    private void swap(String sql, UUID from, UUID to) {
        jdbc.sql(sql).param("to", to).param("from", from).update();
    }

    private static final UUID ZERO = new UUID(0L, 0L);

    @Test
    void msaExtractsTypedLegalFactsAndRelationships() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        var liability = semantics.clauses().stream()
                .filter(clause -> clause.primaryConcept()
                        .map(concept -> concept == LegalConcept.LIMITATION_OF_LIABILITY)
                        .orElse(false))
                .findFirst()
                .orElseThrow();
        var capFacts = liability.facts().stream()
                .filter(fact -> fact.factSchema().equals("liability-facts"))
                .findFirst()
                .orElseThrow();
        assertThat(capFacts.values()).containsEntry("liabilityCapMultiple", "3");
        assertThat(capFacts.values()).containsEntry("capBasis", "PRECEDING_12_MONTH_FEES");
        assertThat(capFacts.values()).containsEntry("consequentialDamagesExcluded", "true");
        assertThat(capFacts.values()).containsEntry("dataBreachCarveout", "true");

        var termination = semantics.clauses().stream()
                .filter(clause -> clause.classification().concepts()
                        .contains(LegalConcept.TERM_TERMINATION))
                .filter(clause -> clause.facts().stream()
                        .anyMatch(fact -> fact.factSchema().equals("termination-facts")))
                .findFirst()
                .orElseThrow();
        var noticeFacts = termination.facts().stream()
                .filter(fact -> fact.factSchema().equals("termination-facts"))
                .findFirst()
                .orElseThrow();
        assertThat(noticeFacts.values()).containsEntry("noticePeriodDays", "60");

        assertThat(semantics.relationships()).isNotEmpty();
        var survivalRelationships = semantics.relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.SURVIVES)
                .toList();
        assertThat(survivalRelationships).isNotEmpty();
    }

    @Test
    void dualConceptClauseKeepsPrimaryAndSecondary() throws Exception {
        var bytes = fixtureBytes("m5-dual-concept.docx");
        var ingested = ingestionService.ingest("m5-dual-concept.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        var lol = semantics.clauses().stream()
                .filter(clause -> clause.contentText().contains("aggregate liability"))
                .findFirst()
                .orElseThrow();
        assertThat(lol.primaryConcept()).contains(LegalConcept.LIMITATION_OF_LIABILITY);
        assertThat(lol.secondaryConcepts()).contains(LegalConcept.INDEMNIFICATION);
    }

    @Test
    void ambiguousPartyRolesRouteToHumanCorrection() throws Exception {
        var bytes = fixtureBytes("m5-ambiguous-party.docx");
        var ingested = ingestionService.ingest("m5-ambiguous-party.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.NDA));

        assertThat(semantics.quality().notes()).contains(ReviewQualityNote.AMBIGUOUS_PARTY_ROLES);
        var acme = semantics.parties().stream()
                .filter(party -> party.rawName().equals("Acme Corp"))
                .findFirst()
                .orElseThrow();
        assertThat(acme.machineRoles()).containsExactly(PartyRole.PARTY_A);

        var corrected = enrichmentService.correctPartyRole(ingested.contractId(), 1,
                acme.id(), List.of(PartyRole.CUSTOMER));
        var reloadedAcme = corrected.parties().stream()
                .filter(party -> party.rawName().equals("Acme Corp"))
                .findFirst()
                .orElseThrow();
        assertThat(reloadedAcme.effectiveRoles()).containsExactly(PartyRole.CUSTOMER);
        assertThat(reloadedAcme.machineRoles()).containsExactly(PartyRole.PARTY_A);
        assertThat(reloadedAcme.roleCorrected()).isTrue();
    }

    @Test
    void typedCapFactCarriesNoAcceptabilityJudgment() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        var liabilityFacts = semantics.clauses().stream()
                .flatMap(clause -> clause.facts().stream())
                .filter(fact -> fact.factSchema().equals("liability-facts"))
                .findFirst()
                .orElseThrow();
        assertThat(liabilityFacts.values()).containsEntry("liabilityCapMultiple", "3");
        assertThat(liabilityFacts.values().keySet()).noneMatch(field ->
                field.toLowerCase().contains("risk") || field.toLowerCase().contains("violation")
                        || field.toLowerCase().contains("policy") || field.toLowerCase().contains("recommend"));
        assertThat(semantics.quality().notes()).noneMatch(note ->
                note.name().toLowerCase().contains("risk") || note.name().contains("VIOLATION"));
    }

    @Test
    void generativeExtractionServesOpenEndedCapValue() throws Exception {
        var bytes = fixtureBytes("m5-dual-concept.docx");
        var ingested = ingestionService.ingest("m5-dual-concept.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        var liability = semantics.clauses().stream()
                .filter(clause -> clause.contentText().contains("aggregate liability"))
                .findFirst()
                .orElseThrow();
        var capFacts = liability.facts().stream()
                .filter(fact -> fact.factSchema().equals("liability-facts"))
                .findFirst()
                .orElseThrow();
        assertThat(capFacts.values()).containsEntry("liabilityCapMultiple", "4");
        assertThat(capFacts.values()).containsEntry("capBasis", "PRECEDING_12_MONTH_FEES");
        var interpretation = liability.interpretations().stream()
                .filter(candidate -> candidate.field().equals("liabilityCapMultiple"))
                .findFirst()
                .orElseThrow();
        assertThat(interpretation.kind()).isEqualTo(InterpretationKind.GENERATIVE_EXTRACTION);
        assertThat(interpretation.verification()).isEqualTo(VerificationState.VERIFIED);
        assertThat(interpretation.verificationExecutionId()).isNotNull();
    }

    @Test
    void conceptAndFactCorrectionsCompose() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));
        var liability = semantics.clauses().stream()
                .filter(clause -> clause.primaryConcept()
                        .map(concept -> concept == LegalConcept.LIMITATION_OF_LIABILITY)
                        .orElse(false))
                .findFirst()
                .orElseThrow();

        var afterConcept = enrichmentService.correctClause(ingested.contractId(), 1,
                liability.id(), FactCorrection.ofPrimary(LegalConcept.INDEMNIFICATION));
        var afterFacts = enrichmentService.correctClause(ingested.contractId(), 1,
                liability.id(), FactCorrection.ofFacts(Map.of("liabilityCapMultiple", "2")));

        var reloaded = enrichmentService.getSemantics(ingested.contractId(), 1);
        var corrected = reloaded.clauses().stream()
                .filter(clause -> clause.id().equals(liability.id()))
                .findFirst()
                .orElseThrow();

        assertThat(corrected.correction().primaryConcept())
                .isEqualTo(LegalConcept.INDEMNIFICATION.name());
        assertThat(corrected.correction().factValues())
                .containsEntry("liabilityCapMultiple", "2");
        assertThat(corrected.conceptCorrected()).isTrue();
        assertThat(corrected.factCorrected()).isTrue();

        // a third correction (fact B) keeps concept + fact A
        var afterThird = enrichmentService.correctClause(ingested.contractId(), 1,
                liability.id(), FactCorrection.ofFacts(Map.of("capBasis", "ANNUAL_FEES")));
        var reloadedThird = afterThird.clauses().stream()
                .filter(clause -> clause.id().equals(liability.id()))
                .findFirst()
                .orElseThrow()
                .correction();
        assertThat(reloadedThird.primaryConcept())
                .isEqualTo(LegalConcept.INDEMNIFICATION.name());
        assertThat(reloadedThird.factValues())
                .containsEntry("liabilityCapMultiple", "2")
                .containsEntry("capBasis", "ANNUAL_FEES");

        // clearing one fact field leaves the concept + other facts intact
        var afterClear = enrichmentService.correctClause(ingested.contractId(), 1,
                liability.id(), new FactCorrection(null,
                        java.util.Collections.singletonMap("capBasis", null),
                        UUID.randomUUID()));
        var cleared = afterClear.clauses().stream()
                .filter(clause -> clause.id().equals(liability.id()))
                .findFirst()
                .orElseThrow()
                .correction();
        assertThat(cleared.primaryConcept())
                .isEqualTo(LegalConcept.INDEMNIFICATION.name());
        assertThat(cleared.factValues())
                .containsEntry("liabilityCapMultiple", "2")
                .doesNotContainKey("capBasis");

        // re-enrichment: machine layer refreshed, all corrections reapplied
        var afterReEnrich = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));
        var machineAgain = afterReEnrich.clauses().stream()
                .filter(clause -> clause.headingNodeId().equals(liability.headingNodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(machineAgain.correction().primaryConcept())
                .isEqualTo(LegalConcept.INDEMNIFICATION.name());
        assertThat(machineAgain.correction().factValues())
                .containsEntry("liabilityCapMultiple", "2");
        assertThat(machineAgain.effectiveFacts().stream()
                .findFirst().orElseThrow().values())
                .containsEntry("liabilityCapMultiple", "2");
    }

    @Test
    void signatureBlocksAreNotClauses() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        assertThat(semantics.clauses()).allSatisfy(clause ->
                assertThat(clause.regionKind())
                        .isNotEqualTo(ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK));
        var kinds = semantics.clauses().stream()
                .map(ClauseSemantics::regionKind)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(kinds).isEqualTo(java.util.Set.of(
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE,
                ContractRegionSegmenter.SemanticRegionKind.SCHEDULE));
        // the honest unclassified signal now comes only from real clauses/schedules
        assertThat(semantics.quality().notes())
                .doesNotContain(ReviewQualityNote.PROVIDER_DEGRADATION);
    }

    @Test
    void provenanceAnchorsResolveAgainstCanonicalStructure() throws Exception {
        var bytes = fixtureBytes("m5-msa-services.docx");
        var ingested = ingestionService.ingest("m5-msa-services.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        var structure = queryService.getStructure(ingested.contractId(), 1);
        var knownNodeIds = structure.nodes().stream()
                .map(DocumentNode::id)
                .collect(java.util.stream.Collectors.toSet());

        var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.MSA));

        // defined term → defining node + reference nodes, all canonical
        for (var term : semantics.definedTerms()) {
            assertThat(knownNodeIds).contains(term.definingNodeId());
            assertThat(knownNodeIds).containsAll(term.referenceNodeIds());
        }
        var fees = semantics.definedTerms().stream()
                .filter(term -> term.term().equals("Fees"))
                .findFirst()
                .orElseThrow();
        assertThat(fees.referenceNodeIds()).isNotEmpty();

        // cross-reference → source node + resolved target node, both canonical
        var resolved = semantics.references().stream()
                .filter(ContractReference::resolved)
                .findFirst()
                .orElseThrow();
        assertThat(knownNodeIds).contains(resolved.sourceNodeId(), resolved.targetNodeId());

        // typed fact → supporting source nodes (exact clause region anchors)
        var liability = semantics.clauses().stream()
                .filter(clause -> clause.primaryConcept()
                        .map(concept -> concept == LegalConcept.LIMITATION_OF_LIABILITY)
                        .orElse(false))
                .findFirst()
                .orElseThrow();
        var capFact = liability.facts().stream()
                .filter(fact -> fact.factSchema().equals("liability-facts"))
                .findFirst()
                .orElseThrow();
        var capInterpretation = liability.interpretations().stream()
                .filter(interpretation ->
                        interpretation.field().equals("liabilityCapMultiple"))
                .findFirst()
                .orElseThrow();
        assertThat(capInterpretation.sourceNodeIds()).isNotEmpty();
        assertThat(knownNodeIds).containsAll(capInterpretation.sourceNodeIds());
        assertThat(knownNodeIds).contains(liability.headingNodeId());

        // party provenance is canonical too
        for (var party : semantics.parties()) {
            assertThat(knownNodeIds).containsAll(party.interpretations().stream()
                    .flatMap(interpretation -> interpretation.sourceNodeIds().stream())
                    .toList());
        }
    }

    @Test
    void crossOrganizationSemanticStateIsInvisible() throws Exception {
        var bytes = fixtureBytes("m5-nda-counterparty.docx");
        var ingested = ingestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        identityService.switchTo(UUID.randomUUID());

        assertThatThrownBy(() -> enrichmentService.getSemantics(ingested.contractId(), 1))
                .isInstanceOf(ContractVersionNotFoundException.class);
        assertThatThrownBy(() -> enrichmentService.correctPartyRole(ingested.contractId(), 1,
                UUID.randomUUID(), List.of(PartyRole.PARTY_B)))
                .isInstanceOf(ContractVersionNotFoundException.class);
        identityService.reset();
        var restored = enrichmentService.enrich(ingested.contractId(), 1,
                ReviewProfile.minimal(ContractType.NDA));
        assertThat(restored.enrichmentStatus()).isEqualTo(EnrichmentStatus.READY);
    }

    private byte[] fixtureBytes(String name) throws Exception {
        var fixture = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", name));
        return fixture;
    }

    private String metadataByField(ContractSemantics semantics, MetadataField field) {
        return semantics.metadata().stream()
                .filter(fact -> fact.field() == field)
                .map(MetadataFact::effectiveValue)
                .findFirst()
                .orElse(null);
    }
}
