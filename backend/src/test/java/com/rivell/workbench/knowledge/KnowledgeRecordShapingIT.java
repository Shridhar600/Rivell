package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * The blind-corpus acceptance for one knowledge record per playbook position / clause
 * wording variant. Four real documents (two DOCX playbooks, one XLSX playbook, one XLSX
 * clause library) go through the real ingestion flow with a deterministic fake for Jev.
 *
 * <p>Evidence this locks down: the old compiler produced 461 candidates for 119 positions,
 * detached table rows from their topic heading, treated an XLSX sheet's first non-empty row
 * as a header, and had no rule/not-rule step. Here each position table is one candidate
 * with role-tagged fields, sheet preamble and non-rule sheets never become candidates, and
 * clause-library variant rows become WORDING candidates while drafting notes stay units.
 */
@SpringBootTest
@Testcontainers
class KnowledgeRecordShapingIT {

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
    static class CorpusIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy corpusModelPolicy() {
            return asset -> new ModelBinding("corpus", "corpus-model");
        }

        @Bean
        @Primary
        CorpusDecisionProvider corpusDecisionProvider() {
            return new CorpusDecisionProvider();
        }

        @Bean
        @Primary
        GenerativeReasoningProvider corpusGenerativeProvider() {
            return new CorpusGenerativeProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentityProvider mutableIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    /**
     * Deterministic stand-in for Jev: reads only the state the decision carries. Kind
     * answers follow the record's own wording; unknown labels answer OTHER.
     */
    static final class CorpusDecisionProvider implements SemanticDecisionProvider {

        // compilation runs decisions concurrently: counters must be thread-safe
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger kindDecisions = new AtomicInteger();
        final AtomicInteger labelDecisions = new AtomicInteger();
        final AtomicInteger topicDecisions = new AtomicInteger();
        volatile boolean failKindCalls;

        void reset() {
            executions.set(0);
            kindDecisions.set(0);
            labelDecisions.set(0);
            topicDecisions.set(0);
            failKindCalls = false;
        }

        void failKindCalls() {
            failKindCalls = true;
        }

        @Override
        public String id() {
            return "corpus";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            executions.incrementAndGet();
            var names = request.pack().questions().keySet();
            if (names.contains("record_kind")) {
                kindDecisions.incrementAndGet();
                if (failKindCalls) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "record kind provider down");
                }
            }
            if (names.stream().anyMatch(name -> name.startsWith("label_"))) {
                labelDecisions.incrementAndGet();
            }
            if (names.stream().anyMatch(name -> name.startsWith("topic_"))) {
                topicDecisions.incrementAndGet();
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = entry.getValue();
                if (question instanceof ChoiceQuestion choice) {
                    answers.put(entry.getKey(), choice(
                            choice, selected(entry.getKey(), choice, request.state().payload())));
                } else {
                    answers.put(entry.getKey(),
                            new YesNoAnswer(probability(entry.getKey(), request.state().payload())));
                }
            }
            return new ProviderExecutionResult(answers, "corpus-resolved-1", new TokenUsage(0, 0));
        }

        private static ChoiceAnswer choice(ChoiceQuestion question, String selected) {
            var option = question.criteria().containsKey(selected)
                    ? selected
                    : question.criteria().keySet().iterator().next();
            var probabilities = new LinkedHashMap<String, Double>();
            question.criteria().keySet().forEach(candidate ->
                    probabilities.put(candidate, candidate.equals(option) ? 1.0 : 0.0));
            return new ChoiceAnswer(option, 1.0, probabilities);
        }

        private static String selected(String name, ChoiceQuestion question, Object state) {
            return switch (name) {
                case "artifact_class" -> declaredType(state);
                case "record_kind" -> recordKind(state);
                default -> name.startsWith("label_")
                        ? labelRole(question)
                        : question.criteria().keySet().iterator().next();
            };
        }

        /**
         * Real Jev (dev eval 2026-10-11) read playbook A's approval-matrix column
         * "Situation" (values "Fallback 1 used", …) as PREFERRED, and (2026-10-12) clause
         * library D's "Variant" (values "Preferred", "Fallback") as APPLICABILITY. Every
         * other unknown label answers OTHER.
         */
        private static String labelRole(ChoiceQuestion question) {
            var instructions = question.instructions();
            if (instructions.contains("\"Situation\"") || instructions.contains("\"Our stance\"")) {
                return "PREFERRED";
            }
            if (instructions.contains("\"Can live with\"")) {
                return "FALLBACK";
            }
            if (instructions.contains("\"Never\"")) {
                return "PROHIBITED";
            }
            return instructions.contains("\"Which deals\"") || instructions.contains("\"Variant\"")
                    ? "APPLICABILITY" : "OTHER";
        }

        private static String declaredType(Object state) {
            if (state instanceof Map<?, ?> payload && payload.get("declaredType") instanceof String type) {
                return type;
            }
            return "PLAYBOOK";
        }

        /**
         * The fake's kind rule, mirroring what a calibrated model should do: notes and
         * process/metadata wording are never positions.
         */
        private static String recordKind(Object state) {
            var sectionPath = stateValue(state, "sectionPath").toLowerCase(Locale.ROOT);
            var text = stateText(state);
            if (text.contains("drafting note") || text.contains("note on the fallback")) {
                return "GUIDANCE";
            }
            if (sectionPath.contains("clauses")) {
                return "WORDING";
            }
            if (text.contains("document owner") || text.contains("classification")
                    || text.contains("related document")
                    || text.contains("effective date") && text.contains("approved by")) {
                return "DOCUMENT_CONTROL";
            }
            if (text.contains("how to use") || text.contains("how we use")
                    || text.contains("who to contact") || text.contains("version history")
                    || text.contains("review and version") || text.contains("definitions")
                    || text.contains("purpose") || text.contains("relationship")) {
                return "GUIDANCE";
            }
            if (text.contains("approval matrix") || text.contains("approval threshold")
                    || text.contains("who signs off") || text.contains("prepared by")) {
                return "ESCALATION";
            }
            if (text.contains("our position") || text.contains("we can accept")
                    || text.contains("we do not cross this line")) {
                return "POSITION";
            }
            return "GUIDANCE";
        }

        private static String stateValue(Object state, String key) {
            if (state instanceof Map<?, ?> payload && payload.get(key) instanceof String value) {
                return value;
            }
            return "";
        }

        private static double probability(String name, Object state) {
            var text = stateText(state);
            return switch (name) {
                case "role_preferred" -> text.contains("our position") || text.contains("preferred")
                        ? 0.9 : 0.02;
                case "role_fallback" -> text.contains("we can accept") || text.contains("fallback")
                        ? 0.9 : 0.02;
                case "role_prohibited" -> text.contains("do not cross") || text.contains("prohibited")
                        ? 0.9 : 0.02;
                case "role_escalation" -> text.contains("escalat") ? 0.9 : 0.02;
                case "role_commentary" -> text.contains("commentary") ? 0.9 : 0.02;
                case "requires_approval" -> 0.02;
                case "narrows_scope" -> 0.02;
                case "source_supports" -> 0.9;
                default -> 0.02;
            };
        }

        private static String stateText(Object state) {
            if (!(state instanceof Map<?, ?> payload)) {
                return "";
            }
            var combined = new StringBuilder();
            for (var value : payload.values()) {
                if (value instanceof String text) {
                    combined.append(text).append('\n');
                }
            }
            return combined.toString().toLowerCase(Locale.ROOT);
        }
    }

    static final class CorpusGenerativeProvider implements GenerativeReasoningProvider {

        @Override
        public String id() {
            return "corpus";
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            return new GenerationResult(
                    Map.of("escalationTrigger", "Escalate to general counsel."),
                    "corpus-generated-1", new TokenUsage(0, 0));
        }
    }

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    CorpusDecisionProvider provider;

    @Autowired
    MutableIdentityProvider identityProvider;

    @Test
    void docxPlaybookProducesOneCandidatePerPositionWithRoleTaggedFields() throws Exception {
        provider.reset();
        var artifact = ingest("fixtures/blind-a-negotiation-playbook.docx",
                "a-negotiation-playbook.docx", ArtifactType.PLAYBOOK);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.EXTRACTED);
        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("one candidate per position table, not one per table row")
                .hasSize(25);
        assertThat(candidates).allSatisfy(candidate ->
                assertThat(candidate.effectivePositions())
                        .contains(PositionRole.PREFERRED, PositionRole.FALLBACK,
                                PositionRole.PROHIBITED));

        var liability = candidates.stream()
                .filter(candidate -> "P1. Limitation of liability".equals(candidate.rawLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(liability.payload().columns())
                .containsKeys("Standard position", "Fallback 1", "Fallback 2",
                        "Walk-away / red line", "Escalation", "Drafting note");
        assertThat(liability.effectivePositions())
                .contains(PositionRole.PREFERRED, PositionRole.FALLBACK, PositionRole.PROHIBITED,
                        PositionRole.ESCALATION, PositionRole.COMMENTARY);
        assertThat(liability.effectiveEscalationTrigger()).isNotBlank();
        assertThat(liability.effectiveContentText()).contains("Cap may be 12 months fees");

        var units = queryService.getUnits(artifact.id());
        assertThat(units.stream()
                .filter(unit -> unit.contentText() != null && !unit.contentText().isBlank())
                .allMatch(unit -> unit.recordKind() != null))
                .as("every record with content keeps its kind; non-position records stay units")
                .isTrue();
        assertThat(units.stream()
                .filter(unit -> unit.recordKind() == KnowledgeRecordKind.POSITION))
                .hasSize(25);
        assertThat(units.stream()
                .filter(unit -> unit.recordKind() == KnowledgeRecordKind.POSITION)
                .allMatch(unit -> unit.sourceNodeIds().size() > 1))
                .as("position provenance resolves beyond the table node alone")
                .isTrue();

        System.out.println("JE-CALLS fixture A: total=" + provider.executions.get()
                + " kind=" + provider.kindDecisions.get()
                + " label=" + provider.labelDecisions.get()
                + " topic=" + provider.topicDecisions.get());
        assertThat(provider.labelDecisions.get())
                .as("unknown labels are one decision per artifact")
                .isEqualTo(1);
        var contentUnits = units.stream()
                .filter(unit -> unit.contentText() != null && !unit.contentText().isBlank())
                .count();
        assertThat(provider.kindDecisions.get())
                .as("one kind decision per non-position record, never per cell")
                .isEqualTo((int) contentUnits - 25);
    }

    @Test
    void xlsxPlaybookProducesOneCandidatePerPositionRowAndNoCandidatesFromOtherSheets()
            throws Exception {
        provider.reset();
        var artifact = ingest("fixtures/blind-b-negotiation-playbook.xlsx",
                "b-negotiation-playbook.xlsx", ArtifactType.PLAYBOOK);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.EXTRACTED);
        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("22 rows on the Positions sheet, one candidate each")
                .hasSize(22);
        assertThat(candidates).allSatisfy(candidate ->
                assertThat(candidate.effectivePositions())
                        .contains(PositionRole.PREFERRED, PositionRole.FALLBACK,
                                PositionRole.PROHIBITED));

        var units = queryService.getUnits(artifact.id());
        var candidateUnits = candidates.stream()
                .map(candidate -> units.stream()
                        .filter(unit -> unit.id().equals(candidate.extractionUnitId()))
                        .findFirst().orElseThrow())
                .toList();
        assertThat(candidateUnits).allSatisfy(unit ->
                assertThat(unit.title()).isEqualTo("Positions"));
        assertThat(units.stream().filter(unit -> "About this guide".equals(unit.title())))
                .as("the About sheet is kept as units")
                .isNotEmpty();
        assertThat(units.stream().filter(unit -> "Escalation".equals(unit.title())))
                .as("the Escalation sheet is kept as units")
                .isNotEmpty();
        assertThat(units.stream().filter(unit -> "Notes & contacts".equals(unit.title())))
                .as("the Notes & contacts sheet is kept as units")
                .isNotEmpty();
        assertThat(units.stream()
                .filter(unit -> "About this guide".equals(unit.title()))
                .noneMatch(unit -> unit.recordKind() == KnowledgeRecordKind.POSITION))
                .as("the About sheet is never a position")
                .isTrue();
        assertThat(units.stream()
                .filter(unit -> "Escalation".equals(unit.title()))
                .noneMatch(unit -> unit.recordKind() == KnowledgeRecordKind.POSITION))
                .as("the Escalation sheet is never a position")
                .isTrue();
        assertThat(units.stream()
                .filter(unit -> "Notes & contacts".equals(unit.title()))
                .noneMatch(unit -> unit.recordKind() == KnowledgeRecordKind.POSITION))
                .as("the Notes & contacts sheet is never a position")
                .isTrue();
        assertThat(units.stream()
                .filter(unit -> unit.contentText() != null && !unit.contentText().isBlank())
                .allMatch(unit -> unit.recordKind() != null))
                .as("non-rule sheet content is kept as units, with its kind")
                .isTrue();

        System.out.println("JE-CALLS fixture B: total=" + provider.executions.get()
                + " kind=" + provider.kindDecisions.get()
                + " label=" + provider.labelDecisions.get()
                + " topic=" + provider.topicDecisions.get());
        assertThat(provider.labelDecisions.get()).isEqualTo(1);
    }

    @Test
    void xlsxClauseLibraryProducesOneCandidatePerWordingVariant() throws Exception {
        provider.reset();
        var artifact = ingest("fixtures/blind-d-clause-library.xlsx",
                "d-clause-library.xlsx", ArtifactType.CLAUSE_LIBRARY);

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("29 variant rows; the 14 drafting notes and the fallback note are not wording")
                .hasSize(29);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.payload().columns()).containsKey("Approved language");
            assertThat(candidate.effectiveContentText())
                    .contains(candidate.payload().columns().get("Approved language"));
        });

        var units = queryService.getUnits(artifact.id());
        var wording = units.stream()
                .filter(unit -> unit.recordKind() == KnowledgeRecordKind.WORDING)
                .toList();
        assertThat(wording).hasSize(29);
        assertThat(candidates.stream()
                .map(candidate -> wording.stream()
                        .anyMatch(unit -> unit.id().equals(candidate.extractionUnitId())))
                .allMatch(Boolean::booleanValue))
                .as("every wording candidate sits on a WORDING unit")
                .isTrue();
    }

    @Test
    void docxProsePlaybookKeepsDocumentControlHowToAndContactsOutOfCandidates() throws Exception {
        provider.reset();
        var artifact = ingest("fixtures/blind-c-negotiation-playbook.docx",
                "c-negotiation-playbook.docx", ArtifactType.PLAYBOOK);

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("prose positions are candidates")
                .isNotEmpty();
        assertThat(candidates).noneMatch(candidate ->
                candidate.effectiveContentText() != null
                        && candidate.effectiveContentText().contains("Document owner"));
        assertThat(candidates).noneMatch(candidate ->
                candidate.rawLabel() != null && candidate.rawLabel().contains("How we use this document"));
        assertThat(candidates).noneMatch(candidate ->
                candidate.rawLabel() != null && candidate.rawLabel().contains("Who to contact"));
        assertThat(candidates).noneMatch(candidate ->
                candidate.rawLabel() != null && candidate.rawLabel().contains("Review and version history"));

        var units = queryService.getUnits(artifact.id());
        assertThat(units)
                .as("the non-rule sections are kept with their kind, not dropped")
                .anyMatch(unit -> unit.recordKind() == KnowledgeRecordKind.DOCUMENT_CONTROL)
                .anyMatch(unit -> unit.recordKind() == KnowledgeRecordKind.GUIDANCE);
    }

    @Test
    void kindProviderFailureKeepsEveryRecordAsACandidate() throws Exception {
        provider.reset();
        provider.failKindCalls();
        var artifact = ingest("fixtures/blind-a-negotiation-playbook.docx",
                "a-degraded.docx", ArtifactType.PLAYBOOK);

        var units = queryService.getUnits(artifact.id());
        long contentUnits = units.stream()
                .filter(unit -> unit.contentText() != null && !unit.contentText().isBlank())
                .count();
        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("fail open: a kind-provider failure must never drop a record")
                .hasSize((int) contentUnits);
        assertThat(candidates.stream()
                .filter(candidate -> candidate.reviewReasons()
                        .contains(ReviewReason.SEMANTIC_ANALYSIS_UNAVAILABLE))
                .count())
                .as("every record whose kind could not be decided says so")
                .isEqualTo(contentUnits - 25);
    }

    @Test
    void sparseXlsxCellsAndOmittedRowsKeepTheirRealColumns() throws Exception {
        provider.reset();
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Clauses");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ref");
            header.createCell(1).setCellValue("Clause topic");
            header.createCell(2).setCellValue("Approved language");
            var sparse = sheet.createRow(1);
            sparse.createCell(1).setCellValue("Confidentiality");
            sparse.createCell(2).setCellValue(
                    "Each party shall keep the other party's Confidential Information confidential.");
            var full = sheet.createRow(2);
            full.createCell(0).setCellValue("C02");
            full.createCell(1).setCellValue("Limitation of liability");
            full.createCell(2).setCellValue(
                    "Each party's aggregate liability shall not exceed the fees paid in the preceding 12 months.");
            // row 3 is never materialised: the table ends there
            var note = sheet.createRow(4);
            note.createCell(0).setCellValue("Drafting note: keep this for reference.");
            xssf.write(out);
            workbook = out.toByteArray();
        }
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        var artifact = ingestionService.ingest("sparse-library.xlsx", "application/octet-stream",
                ArtifactType.CLAUSE_LIBRARY, manifest, new ByteArrayInputStream(workbook));

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates).hasSize(2);
        var sparse = candidates.stream()
                .filter(candidate -> "Confidentiality".equals(
                        candidate.payload().columns().get("Clause topic")))
                .findFirst().orElseThrow();
        assertThat(sparse.payload().columns().get("Approved language"))
                .as("an omitted Ref cell must not shift the wording under the topic key")
                .startsWith("Each party shall keep");
        assertThat(candidates.stream()
                .filter(candidate -> "C02".equals(candidate.payload().columns().get("Ref")))
                .findFirst().orElseThrow()
                .payload().columns().get("Approved language"))
                .startsWith("Each party's aggregate liability");
        assertThat(queryService.getUnits(artifact.id()))
                .as("the row after the omitted blank row is a loose record, not a table row")
                .anyMatch(unit -> "Drafting note: keep this for reference.".equals(unit.contentText())
                        && unit.recordKind() == KnowledgeRecordKind.GUIDANCE);
    }

    @Test
    void threeColumnRoleLabeledTableIsOnePositionRecord() throws Exception {
        provider.reset();
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Positions");
            var standard = sheet.createRow(0);
            standard.createCell(0).setCellValue("Standard position");
            standard.createCell(1).setCellValue("Cap at 12 months fees for all agreements.");
            standard.createCell(2).setCellValue("Preferred for all deals");
            var fallback = sheet.createRow(1);
            fallback.createCell(0).setCellValue("Fallback 1");
            fallback.createCell(1).setCellValue("Cap at 18 months fees where required.");
            fallback.createCell(2).setCellValue("Deal desk approval");
            xssf.write(out);
            workbook = out.toByteArray();
        }
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        var artifact = ingestionService.ingest("three-column.xlsx", "application/octet-stream",
                ArtifactType.PLAYBOOK, manifest, new ByteArrayInputStream(workbook));

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("a role-labeled label|value|note table is one position, not one row per position")
                .hasSize(1);
        var candidate = candidates.get(0);
        assertThat(candidate.payload().columns())
                .containsKeys("Standard position", "Fallback 1");
        assertThat(candidate.effectivePositions())
                .containsExactlyInAnyOrder(PositionRole.PREFERRED, PositionRole.FALLBACK);
    }

    @Test
    void twoColumnSubjectTableUnderAHeaderRowIsOnePositionPerRow() throws Exception {
        provider.reset();
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Summary");
            String[][] rows = {
                    {"Clause", "Our position"},
                    {"Liability cap", "Fees paid in the prior 12 months."},
                    {"Indemnity", "Mutual, limited to third-party IP claims."},
                    {"Governing law", "England and Wales."}};
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue(rows[i][0]);
                row.createCell(1).setCellValue(rows[i][1]);
            }
            xssf.write(out);
            workbook = out.toByteArray();
        }
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        var artifact = ingestionService.ingest("summary.xlsx", "application/octet-stream",
                ArtifactType.PLAYBOOK, manifest, new ByteArrayInputStream(workbook));

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("each subject row is its own position, not a field of one record")
                .extracting(KnowledgeCandidate::rawLabel)
                .containsExactlyInAnyOrder("Liability cap", "Indemnity", "Governing law");
        assertThat(candidates).allSatisfy(candidate ->
                assertThat(candidate.effectivePositions()).containsExactly(PositionRole.PREFERRED));
    }

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Test
    void jurisdictionScopeMatchesTheDeclaredSpellingOfANewJurisdiction() throws Exception {
        provider.reset();
        // publishes into its own organization so the other tests' knowledge cannot match
        var shared = identityProvider.current;
        var identity = new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
        identityProvider.use(identity);
        try {
            var newZealand = ingestWithJurisdiction("nz-playbook.docx", "New Zealand");
            var other = ingestWithJurisdiction("other-playbook.docx", "OTHER");
            var candidates = new java.util.ArrayList<KnowledgeCandidate>();
            candidates.addAll(queryService.getCandidates(newZealand.id()));
            candidates.addAll(queryService.getCandidates(other.id()));
            assertThat(candidates).isNotEmpty();
            var publication = publicationService.publish(candidates.stream()
                    .map(candidate -> reviewService.approve(candidate.id(), null).id())
                    .toList(), Map.of(), "jurisdictions");

            assertThat(publishedArtifactIds(identity.organizationId(),
                    publication.publicationNumber(), "NEW_ZEALAND"))
                    .contains(newZealand.id())
                    .doesNotContain(other.id());
            assertThat(publishedArtifactIds(identity.organizationId(),
                    publication.publicationNumber(), "OTHER"))
                    .contains(other.id())
                    .doesNotContain(newZealand.id());
            assertThat(publishedArtifactIds(identity.organizationId(),
                    publication.publicationNumber(), null))
                    .as("an unspecified jurisdiction remains the wildcard")
                    .contains(newZealand.id(), other.id());
        } finally {
            identityProvider.use(shared);
        }
    }

    private KnowledgeArtifact ingestWithJurisdiction(String filename, String jurisdiction)
            throws Exception {
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party",
                jurisdiction, "en", null, null, null);
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-playbook.docx")) {
            assertThat(stream).isNotNull();
            return ingestionService.ingest(filename,
                    "application/octet-stream", ArtifactType.PLAYBOOK, manifest, stream);
        }
    }

    private List<UUID> publishedArtifactIds(
            UUID organizationId, long publicationNumber, String jurisdiction) {
        return queryService.listPublishedCandidatesForOrganization(
                        PublishedKnowledgeQuery.unpublishedNever(organizationId,
                                publicationNumber, Set.of(), null, "MSA_PACK", "MSA",
                                "Customer", "Third-Party", jurisdiction, null))
                .stream()
                .map(candidate -> candidate.candidate().artifactId())
                .toList();
    }

    @Test
    void contractTypeColumnGroupsBecomeOnePositionPerTypeThatAppliesOnlyToThatType()
            throws Exception {
        provider.reset();
        // publishes: its own organization keeps the knowledge away from the other tests
        var shared = identityProvider.currentIdentity();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        try {
            groupedPlaybookAppliesPerContractType();
        } finally {
            identityProvider.use(shared);
        }
    }

    private void groupedPlaybookAppliesPerContractType() throws Exception {
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Positions");
            String[][] rows = {
                    {"Topic", "NDA", null, "MSA", null, "Governance"},
                    {"Topic", "Preferred", "Walk-away", "Preferred", "Walk-away", "Approver"},
                    {"Confidentiality", "Mutual, 3 years from disclosure.", "Perpetual confidentiality.",
                            "Mutual, 5 years.", "Perpetual confidentiality.", "General Counsel"},
                    {"Limitation of liability", null, null, "Cap at the fees paid in 12 months.",
                            "Unlimited liability.", "General Counsel"}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 1, 2));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 3, 4));
            xssf.write(out);
            workbook = out.toByteArray();
        }
        // the manifest declares no type: only the records' own column groups narrow
        var manifest = new ArtifactManifest(
                Set.of(), null, null, null, null, "en", null, null, null);
        var artifact = ingestionService.ingest("grouped.xlsx", "application/octet-stream",
                ArtifactType.PLAYBOOK, manifest, new ByteArrayInputStream(workbook));

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates)
                .as("one position per topic and contract-type group with a value; the label row is no record")
                .extracting(c -> c.rawLabel() + " " + c.scope().sourceContractTypes())
                .containsExactlyInAnyOrder("Confidentiality [NDA]", "Confidentiality [MSA]",
                        "Limitation of liability [MSA]");
        var ndaConfidentiality = candidates.stream()
                .filter(c -> c.scope().sourceContractTypes().contains("NDA")).findFirst().orElseThrow();
        assertThat(ndaConfidentiality.payload().columns())
                .containsEntry("Preferred", "Mutual, 3 years from disclosure.")
                .containsEntry("Walk-away", "Perpetual confidentiality.")
                .containsEntry("Approver", "General Counsel")
                .doesNotContainValue("Mutual, 5 years.");
        assertThat(ndaConfidentiality.effectivePositions())
                .containsExactlyInAnyOrder(PositionRole.PREFERRED, PositionRole.PROHIBITED,
                        PositionRole.ESCALATION);

        var publication = publicationService.publish(candidates.stream()
                .map(c -> reviewService.approve(c.id(), null).id()).toList(), Map.of(), "v1");
        java.util.function.Function<String, List<String>> applicable = contractType ->
                queryService.listPublishedCandidatesForOrganization(
                                PublishedKnowledgeQuery.unpublishedNever(
                                        identityProvider.currentIdentity().organizationId(),
                                        publication.publicationNumber(), Set.of(), null, null,
                                        contractType, null, null, null, null))
                        .stream().map(PublishedCandidate::candidate)
                        .filter(c -> c.artifactId().equals(artifact.id()))
                        .map(c -> c.rawLabel() + " " + c.scope().sourceContractTypes())
                        .toList();
        assertThat(applicable.apply("MNDA"))
                .as("a mutual NDA gets the NDA group's position only")
                .containsExactly("Confidentiality [NDA]");
        assertThat(applicable.apply("MSA"))
                .containsExactlyInAnyOrder("Confidentiality [MSA]", "Limitation of liability [MSA]");
        assertThat(applicable.apply("SOW"))
                .as("a statement of work is made under the MSA, so the MSA group governs it too")
                .containsExactlyInAnyOrder("Confidentiality [MSA]", "Limitation of liability [MSA]");
        assertThat(applicable.apply("OTHER"))
                .as("a type the product does not model cannot be excluded by a record's own types")
                .hasSize(3);
    }

    @Test
    void labelsTheModelResolvedStillTagThePositionAfterPersistence() throws Exception {
        provider.reset();
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Stances");
            String[][] rows = {
                    {"What it's about", "Which deals", "Our stance", "Can live with", "Never"},
                    // the fake kind decision reads "our position" as a stance
                    {"Payment terms", "MSA, SOW", "Our position: Net 30 days from a valid invoice.",
                            "Net 45 days.", "Net 90 or later."}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            xssf.write(out);
            workbook = out.toByteArray();
        }
        var manifest = new ArtifactManifest(Set.of(), null, null, null, null, "en", null, null, null);
        var artifact = ingestionService.ingest("stances.xlsx", "application/octet-stream",
                ArtifactType.PLAYBOOK, manifest, new ByteArrayInputStream(workbook));

        var candidate = queryService.getCandidates(artifact.id()).getFirst();
        assertThat(candidate.effectivePositionFieldValues())
                .as("review reads the resolved labels as stances, not one unlabelled position")
                .containsEntry(PositionRole.PREFERRED, List.of("Our position: Net 30 days from a valid invoice."))
                .containsEntry(PositionRole.FALLBACK, List.of("Net 45 days."))
                .containsEntry(PositionRole.PROHIBITED, List.of("Net 90 or later."));
        assertThat(candidate.scope().sourceContractTypes()).containsExactlyInAnyOrder("MSA", "SOW");
    }

    @Test
    void aLabelMisreadAsApplicabilityDoesNotHideThePosition() throws Exception {
        provider.reset();
        byte[] workbook;
        try (var xssf = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = xssf.createSheet("Library");
            String[][] rows = {
                    {"Ref", "Clause topic", "Variant", "Approved language"},
                    {"C08", "Foreground IP", "Preferred",
                            "Our position: all deliverables vest in the Customer on creation."}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            xssf.write(out);
            workbook = out.toByteArray();
        }
        var manifest = new ArtifactManifest(Set.of(), null, null, null, null, "en", null, null, null);
        var artifact = ingestionService.ingest("library.xlsx", "application/octet-stream",
                ArtifactType.CLAUSE_LIBRARY, manifest, new ByteArrayInputStream(workbook));

        var candidate = queryService.getCandidates(artifact.id()).getFirst();
        assertThat(candidate.effectiveFieldRoles()).containsEntry("Variant", "APPLICABILITY");
        assertThat(candidate.scope().sourceContractTypes())
                .as("\"Preferred\" names no contract type, so the record applies wherever its source does")
                .isEmpty();
    }

    private KnowledgeArtifact ingest(String fixture, String filename, ArtifactType type)
            throws Exception {
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return ingestionService.ingest(
                    filename, "application/octet-stream", type, manifest, stream);
        }
    }
}
