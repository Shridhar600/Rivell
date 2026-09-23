package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.RatingQuestion;
import com.rivell.workbench.intelligence.RatingAnswer;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * Opt-in live Jev smoke. Runs ONLY when TYPESAFE_API_KEY is present in the environment;
 * normal verification never requires network access or credentials.
 * Synthetic neutral data only — no legal or customer content.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class JevLiveSmokeTest {

    private static final String API_KEY = System.getenv("TYPESAFE_API_KEY");

    private JevProvider provider() {
        return new JevProvider(
                new JevProperties("https://api.typesafe.ai", API_KEY, "jev-latest",
                        Duration.ofSeconds(5), Duration.ofSeconds(20)),
                new ObjectMapper());
    }

    @Test
    void discoversModels() {
        var models = provider().listModels();

        assertThat(models).extracting(JevRequestDtos.ModelMetadata::name).isNotEmpty();
        models.forEach(model -> System.out.println(
                "MODEL name=" + model.name() + " release=" + model.releaseDate()));
    }

    @Test
    void executesMixedPrimitivesOnSyntheticState() {
        var request = new DecisionExecutionRequest(
                "jev-latest",
                syntheticState(),
                new QuestionPack(Map.of(
                        "mentions_confidentiality", new YesNoQuestion(
                                "Does this clause text contain an obligation of confidentiality?",
                                "The text contains an explicit confidentiality obligation.",
                                "No confidentiality obligation present."),
                        "clause_concept", new ChoiceQuestion(
                                "Which legal concept dominates this clause?",
                                linkedMap(Map.of(
                                        "confidentiality", "Confidentiality obligation.",
                                        "limitation_of_liability", "Damage limitation.",
                                        "governing_law", "Governing jurisdiction."))),
                        "drafting_specificity", new RatingQuestion(
                                "How specific is the drafting?",
                                List.of("Generic", "Specific obligations", "Detailed with conditions")))));

        var result = provider().execute(request);

        var yesNo = (YesNoAnswer) result.answers().get("mentions_confidentiality");
        var choice = (ChoiceAnswer) result.answers().get("clause_concept");
        var rating = (RatingAnswer) result.answers().get("drafting_specificity");
        System.out.println("resolvedModel=" + result.resolvedModel()
                + " usage=" + result.usage());
        System.out.println("noul=" + yesNo.probability());
        System.out.println("choice=" + choice.selected() + " confidence=" + choice.confidence()
                + " probabilities=" + choice.probabilities());
        System.out.println("rating score=" + rating.expectedScore() + " confidence=" + rating.confidence());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
        assertThat(choice.probabilities()).containsKeys("confidentiality", "limitation_of_liability");
    }

    private SemanticState syntheticState() {
        return new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of(
                        "text", "The supplier shall maintain the confidentiality of the discloser's "
                                + "business information for the duration of this agreement.",
                        "kind", "HEADING_SECTION",
                        "title", "Confidentiality");
            }
        };
    }

    private static Map<String, String> linkedMap(Map<String, String> source) {
        var ordered = new java.util.LinkedHashMap<String, String>();
        source.forEach(ordered::put);
        return ordered;
    }
}
