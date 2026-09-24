package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.provider.JevProvider;
import com.rivell.workbench.intelligence.provider.JevProperties;

/**
 * Opt-in live Jev smoke for the M6 retrieval DecisionSpecs. Runs ONLY when
 * TYPESAFE_API_KEY is present in the environment; normal verification never
 * requires network access or credentials. Fixture text only — no customer content.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class RetrievalLiveSmokeTest {

    private static final String API_KEY = System.getenv("TYPESAFE_API_KEY");

    private JevProvider provider() {
        return new JevProvider(
                new JevProperties("https://api.typesafe.ai", API_KEY, "jev-latest",
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ObjectMapper());
    }

    @Test
    void precedentRelevanceSpecAnswersLiveFixtureState() {
        var spec = M6SemanticSpecs.precedentRelevance();
        var state = new M6SemanticStates.PrecedentRelevanceState(
                "Supplier's total aggregate liability arising out of or related to this "
                        + "Agreement shall not exceed an amount equal to three (3) times the Fees "
                        + "paid or payable in the twelve (12) months immediately preceding the "
                        + "event giving rise to the claim.",
                "LIMITATION_OF_LIABILITY",
                "The total liability of Supplier shall not exceed the fees paid in the "
                        + "preceding 12 months.",
                "LIMITATION_OF_LIABILITY");

        ProviderExecutionResult result = provider().execute(
                new DecisionExecutionRequest("jev-latest", state, spec.pack()));

        var sameIssue = (YesNoAnswer) result.answers().get(M6SemanticSpecs.SAME_LEGAL_ISSUE);
        var sameFunction = (YesNoAnswer) result.answers()
                .get(M6SemanticSpecs.SAME_CLAUSE_FUNCTION);
        var comparable = (YesNoAnswer) result.answers().get(M6SemanticSpecs.COMPARABLE_CONTEXT);
        System.out.println("LIVE-JEV relevance resolvedModel=" + result.resolvedModel()
                + " usage=" + result.usage()
                + " sameLegalIssue=" + sameIssue.probability()
                + " sameClauseFunction=" + sameFunction.probability()
                + " comparableContext=" + comparable.probability());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
    }

    @Test
    void policyCompatibilitySpecAnswersLiveFixtureState() {
        var spec = M6SemanticSpecs.precedentPolicyCompatibility();
        var state = new M6SemanticStates.PolicyCompatibilityState(
                "The liability of Supplier under this Agreement is uncapped and "
                        + "unlimited, however arising.",
                List.of("The total liability of Supplier shall not exceed the fees paid "
                        + "in the preceding 12 months."));

        ProviderExecutionResult result = provider().execute(
                new DecisionExecutionRequest("jev-latest", state, spec.pack()));

        var compatible = (YesNoAnswer) result.answers().get(M6SemanticSpecs.COMPATIBLE_WITH_POLICY);
        System.out.println("LIVE-JEV compatibleWithPolicy=" + compatible.probability()
                + " resolvedModel=" + result.resolvedModel());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
    }
}
