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
            return asset -> new ModelBinding("fake", "fake-model-latest");
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

        assertThat(semantics.quality().notes())
                .contains(ReviewQualityNote.UNCLASSIFIED_REGIONS)
                .doesNotContain(ReviewQualityNote.PROVIDER_DEGRADATION,
                        ReviewQualityNote.UNVERIFIED_FACTS);
        assertThat(semantics.observations().decisionExecutionCount()).isPositive();
    }

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
