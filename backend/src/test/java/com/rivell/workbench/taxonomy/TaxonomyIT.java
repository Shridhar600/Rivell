package com.rivell.workbench.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * The seeded taxonomy is what the product classifies against: Flyway seed → registry →
 * API, and a section classified through the real decision service (provider faked).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TaxonomyIT {

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
    static class FakeJev {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return asset -> new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new CueProvider();
        }
    }

    /** Says yes to liability-cap and damages-exclusion topics when the text talks about them. */
    static final class CueProvider implements SemanticDecisionProvider {

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var text = String.valueOf(((Map<?, ?>) request.state().payload()).get("text")).toLowerCase();
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            request.pack().questions().forEach((name, question) -> {
                if (question instanceof ChoiceQuestion choice) {
                    var probabilities = new LinkedHashMap<String, Double>();
                    choice.criteria().keySet().forEach(key -> probabilities.put(key,
                            key.equals("LIMITATION_OF_LIABILITY") ? 0.9 : 0.1 / (choice.criteria().size() - 1)));
                    answers.put(name, new ChoiceAnswer("LIMITATION_OF_LIABILITY", 0.9, probabilities));
                } else {
                    var yes = name.equals("topic_limitation_of_liability") && text.contains("aggregate liability")
                            || name.equals("topic_exclusion_of_damages") && text.contains("indirect");
                    answers.put(name, new YesNoAnswer(yes ? 0.95 : 0.02));
                }
            });
            return new ProviderExecutionResult(answers, "fake-model", new TokenUsage(1, 1));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TopicClassifier classifier;

    @Test
    void servesTheSeededTaxonomyEveryLeafUnderAParent() throws Exception {
        mockMvc.perform(get("/api/taxonomy/concepts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parents.length()").value(14))
                .andExpect(jsonPath("$.leaves.length()").value(90))
                .andExpect(jsonPath("$.leaves[?(@.key == 'LIMITATION_OF_LIABILITY')].parentKey")
                        .value("LIABILITY_AND_INDEMNITY"))
                .andExpect(jsonPath("$.leaves[?(@.parentKey == null)]").isEmpty())
                .andExpect(jsonPath("$.leaves[?(@.definition == null)]").isEmpty());
    }

    @Test
    void classifiesASectionAgainstEveryLeafAndPicksItsMainTopic() {
        var result = classifier.classify("Limitation of Liability",
                "Each party's aggregate liability shall not exceed the fees paid in the prior twelve months. "
                        + "Neither party is liable for indirect or consequential loss.");

        assertThat(result.topics()).containsExactlyInAnyOrder(
                ConceptKey.of("LIMITATION_OF_LIABILITY"), ConceptKey.of("EXCLUSION_OF_DAMAGES"));
        assertThat(result.primaryTopic()).contains(ConceptKey.of("LIMITATION_OF_LIABILITY"));
        assertThat(result.probabilities()).hasSize(90);
        assertThat(result.decisionExecutions()).isEqualTo(2);
    }

    @Test
    void aHeadingNamingAWholeFamilyCoversEveryLeafOfIt() {
        assertThat(classifier.familyLeaves("G-03  Data protection"))
                .extracting(ConceptKey::value)
                .contains("SECURITY_MEASURES", "PROCESSOR_DUTIES", "PROCESSING_DETAILS")
                .doesNotContain("CONFIDENTIALITY_OBLIGATIONS");
        assertThat(classifier.familyLeaves("Data protection and privacy")).isNotEmpty();
        assertThat(classifier.familyLeaves("Confidentiality period")).as("a leaf, not the family").isEmpty();
        assertThat(classifier.familyLeaves("Limitation of liability")).isEmpty();
        assertThat(classifier.familyLeaves(null)).isEmpty();
    }

    @Test
    void aSectionMatchingNoTopicStaysUnclassified() {
        var result = classifier.classify("Signatures", "Signed by the authorised representatives of the parties.");

        assertThat(result.topics()).isEmpty();
        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.decisionExecutions()).isEqualTo(1);
    }
}
