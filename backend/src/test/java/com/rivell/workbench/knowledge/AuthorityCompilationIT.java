package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.rivell.workbench.platform.docx.DocxTestPackages;
import com.rivell.workbench.taxonomy.CoreConcepts;

/**
 * Part 3 acceptance: what a playbook states with a label is kept, the approval matrix and
 * approval table are read as authority sources rather than published as positions, a document
 * without labels still goes to the model exactly as before, and unmapped authority words keep a
 * draft out of "Review all".
 */
@SpringBootTest
@Testcontainers
class AuthorityCompilationIT {

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
        ModelPolicy authorityModelPolicy() {
            return asset -> new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider authorityDecisionProvider() {
            return new ScriptedProvider();
        }

        @Bean
        @Primary
        MutableIdentityProvider authorityIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    /** A provider that says no to every inferred role, so only source labels can make one. */
    static final class ScriptedProvider implements SemanticDecisionProvider {

        final List<Set<String>> playbookPositionPacks = new ArrayList<>();

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            if (request.pack().questions().keySet().stream().anyMatch(name -> name.startsWith("role_"))) {
                playbookPositionPacks.add(request.pack().questions().keySet());
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                SemanticQuestion question = entry.getValue();
                if (question instanceof ChoiceQuestion choice) {
                    answers.put(entry.getKey(), new ChoiceAnswer(
                            selection(entry.getKey(), choice), 1.0, Map.of(
                                    choice.criteria().keySet().iterator().next(), 1.0)));
                } else {
                    answers.put(entry.getKey(), new YesNoAnswer(probability(entry.getKey())));
                }
            }
            return new ProviderExecutionResult(answers, "fake-resolved-v1", new TokenUsage(120, 8));
        }

        private static String selection(String name, ChoiceQuestion question) {
            if (name.equals("artifact_class")) {
                return "PLAYBOOK";
            }
            if (name.equals("record_kind")) {
                return "POSITION";
            }
            if (name.startsWith("label_")) {
                return "OTHER";
            }
            if (name.equals("variant_role")) {
                return "UNSPECIFIED";
            }
            return question.criteria().keySet().iterator().next();
        }

        private static double probability(String name) {
            if (name.startsWith("topic_")) {
                return 0.9;
            }
            if (name.equals("source_supports")) {
                return 0.9;
            }
            return 0.02;
        }
    }

    @Autowired
    ScriptedProvider fakeProvider;

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
        fakeProvider.playbookPositionPacks.clear();
    }

    @Test
    void explicitLabelsBecomeTypedStancesAndTheMatrixSuppliesTheApproval() throws Exception {
        var artifact = ingest(docx("Authority Playbook",
                "H1:Limitation of liability",
                "P:Applies to: MSA.",
                "P:Preferred position: Cap at the fees paid in the preceding 12 months.",
                "P:Fallback position: Cap at two times the fees paid in the preceding 12 months.",
                "P:Walk-away: Unlimited liability.",
                "P:Approver: General Counsel.",
                "H1:How to use",
                "P:Approval matrix: the Commercial Manager approves fallbacks. "
                        + "The General Counsel approves any matter that reaches a walk-away.",
                "H1:Approval path",
                "P:The lawyer handling the NDA may apply the fallbacks above and record the reason.",
                "T:Routine term, marking, or return mechanics|Reviewing Commercial Legal counsel|Within stated fallback"));

        var liability = entry(artifact, "Limitation of liability");

        // the text states these stances, so no model probability could have produced them
        assertThat(liability.effectivePreferredText())
                .isEqualTo("Cap at the fees paid in the preceding 12 months.");
        assertThat(liability.effectiveFallbackTexts())
                .containsExactly("Cap at two times the fees paid in the preceding 12 months.");
        assertThat(liability.effectiveProhibitedText()).isEqualTo("Unlimited liability.");
        assertThat(liability.effectivePositions()).containsExactlyInAnyOrder(
                PositionRole.PREFERRED, PositionRole.FALLBACK, PositionRole.PROHIBITED);

        // the entry's approver wins (council §A.1); the matrix's fallback rule is kept too
        var fallbackRules = liability.effectiveAuthorityRules().stream()
                .filter(rule -> rule.stances().contains(PositionRole.FALLBACK))
                .toList();
        assertThat(fallbackRules.getFirst().approver()).isEqualTo("General Counsel");
        assertThat(fallbackRules.getFirst().source()).isEqualTo(AuthorityLevel.ENTRY);
        assertThat(fallbackRules).anySatisfy(rule -> {
            assertThat(rule.approver()).isEqualTo("Commercial Manager");
            assertThat(rule.source()).isEqualTo(AuthorityLevel.MATRIX);
        });
        assertThat(liability.effectiveAuthorityConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.stance()).isEqualTo(PositionRole.FALLBACK);
                    assertThat(conflict.approver()).isEqualTo("Commercial Manager");
                    assertThat(conflict.source()).isEqualTo(AuthorityLevel.MATRIX);
                });

        // the matrix and the approval table are authority sources, never published positions
        assertThat(queryService.getCandidates(artifact.id()))
                .extracting(KnowledgeCandidate::rawLabel)
                .doesNotContain("Approval matrix", "Approval path",
                        "Routine term, marking, or return mechanics");
        assertThat(queryService.getUnits(artifact.id()))
                .filteredOn(unit -> unit.contentText() != null
                        && unit.contentText().startsWith("Approval matrix"))
                .singleElement()
                .satisfies(unit -> assertThat(unit.recordKind())
                        .isEqualTo(KnowledgeRecordKind.ESCALATION));
        assertThat(queryService.getCandidates(artifact.id()))
                .extracting(KnowledgeCandidate::rawLabel)
                .doesNotContain("How to use");

        // Jev was asked only what the text left open
        var liabilityPack = fakeProvider.playbookPositionPacks.stream()
                .filter(pack -> pack.contains("role_commentary"))
                .findFirst().orElseThrow();
        assertThat(liabilityPack)
                .doesNotContain("role_preferred", "role_fallback", "role_prohibited",
                        "requires_approval");
    }

    @Test
    void aDocumentWithoutLabelsStillSendsEveryStanceToTheModel() throws Exception {
        var artifact = ingest(docx("Unlabelled Playbook",
                "H1:Confidentiality",
                "P:Confidentiality obligations must survive termination for three years.",
                "H1:Governing law",
                "P:The laws of New South Wales govern this agreement."));

        var pack = fakeProvider.playbookPositionPacks.stream()
                .filter(questions -> questions.contains("role_preferred"))
                .findFirst().orElseThrow();
        assertThat(pack).contains("role_preferred", "role_fallback", "role_prohibited",
                "role_escalation", "role_commentary", "requires_approval");

        // every probability is below the threshold, so nothing is a position by inference
        assertThat(queryService.getCandidates(artifact.id()))
                .allSatisfy(candidate -> {
                    assertThat(candidate.effectivePositions()).isEmpty();
                    assertThat(candidate.effectivePreferredText()).isNull();
                    assertThat(candidate.effectiveAuthorityRules()).isEmpty();
                });
    }

    @Test
    void aPersonCanCorrectTheTypedStancesAndTheApprovalRule() throws Exception {
        var artifact = ingest(docx("Authority Playbook",
                "H1:Limitation of liability",
                "P:Preferred position: Cap at the fees paid in the preceding 12 months.",
                "P:Fallback position: Cap at two times the fees paid in the preceding 12 months.",
                "P:Walk-away: Unlimited liability.",
                "P:Approver: General Counsel."));
        var entry = entry(artifact, "Limitation of liability");
        var correctedRule = new AuthorityRule("Head of Legal",
                java.util.Set.of(PositionRole.FALLBACK),
                "if the cap is below twelve months", AuthorityLevel.ENTRY, "corrected by you");

        reviewService.edit(entry.id(), null, null, null, null,
                new SemanticFields(java.util.Set.of(PositionRole.PREFERRED), null, null, null,
                        Map.of(), false, "Corrected preferred text", List.of("Corrected fallback text"),
                        "", List.of(correctedRule), List.of(), false, List.of(), false),
                "human correction");

        var reloaded = queryService.getCandidate(entry.id());
        assertThat(reloaded.effectivePreferredText()).isEqualTo("Corrected preferred text");
        assertThat(reloaded.effectiveFallbackTexts()).containsExactly("Corrected fallback text");
        assertThat(reloaded.effectiveProhibitedText()).isNull();
        assertThat(reloaded.effectiveAuthorityRules()).containsExactly(correctedRule);
        // the machine reading stays beside the correction
        assertThat(reloaded.semanticFields().preferredText())
                .isEqualTo("Cap at the fees paid in the preceding 12 months.");
        assertThat(reloaded.semanticFields().authorityRules()).isNotEmpty();
    }

    @Test
    void unmappedAuthorityWordsAreFlaggedAndLeftOutOfBulkApproval() throws Exception {
        var artifact = ingest(docx("Authority Playbook",
                "H1:Confidentiality",
                "P:Preferred: Mutual confidentiality for three years.",
                "P:Deal-breaker: the supplier must never use our information for its own purposes."));

        var entry = entry(artifact, "Confidentiality");

        assertThat(entry.semanticFields().unmappedAuthority())
                .containsExactly("Deal-breaker: the supplier must never use our information "
                        + "for its own purposes.");
        assertThat(entry.reviewReasons()).contains(ReviewReason.UNMAPPED_AUTHORITY_WORDS);
        assertThat(entry.reviewCategory()).isEqualTo(ReviewCategory.ELEVATED);

        var result = reviewService.bulkApprove(List.of(entry.id()));
        assertThat(result.approvedIds()).isEmpty();
        assertThat(result.skipped()).containsKey(entry.id());
        assertThat(result.skipped().get(entry.id()))
                .contains("walk-away or an approver Rivell could not map");

        // the person can still open it and approve it by hand
        assertThat(reviewService.approve(entry.id(), null).reviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
    }

    private KnowledgeCandidate entry(KnowledgeArtifact artifact, String title) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> title.equals(candidate.rawLabel()))
                .findFirst().orElseThrow();
    }

    private KnowledgeArtifact ingest(byte[] bytes) {
        try (var stream = new ByteArrayInputStream(bytes)) {
            return ingestionService.ingest("authority-playbook.docx",
                    "application/octet-stream", ArtifactType.PLAYBOOK, null, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A minimal DOCX built from explicit H1 headings, paragraphs and three-column table rows. */
    private static byte[] docx(String title, String... blocks) {
        var body = new StringBuilder();
        body.append(paragraph("Title", title));
        var tableRows = new ArrayList<String[]>();
        for (var block : blocks) {
            if (block.startsWith("H1:")) {
                body.append(paragraph("Heading1", block.substring(3)));
            } else if (block.startsWith("T:")) {
                tableRows.add(block.substring(2).split("\\|", -1));
            } else {
                body.append(paragraph("Normal", block.substring(2)));
            }
        }
        if (!tableRows.isEmpty()) {
            body.append("<w:tbl><w:tblGrid>");
            for (int column = 0; column < tableRows.getFirst().length; column++) {
                body.append("<w:gridCol/>");
            }
            body.append("</w:tblGrid>");
            for (var row : tableRows) {
                body.append("<w:tr>");
                for (var cell : row) {
                    body.append("<w:tc>").append(paragraph("Normal", cell.trim())).append("</w:tc>");
                }
                body.append("</w:tr>");
            }
            body.append("</w:tbl>");
        }
        var document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";
        return DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m4-playbook.docx"), "word/document.xml",
                document.getBytes(StandardCharsets.UTF_8));
    }

    private static String paragraph(String style, String text) {
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">"
                + escape(text) + "</w:t></w:r></w:p>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
