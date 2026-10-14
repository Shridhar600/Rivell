package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.provider.JevProvider;
import com.rivell.workbench.intelligence.provider.JevProperties;

/**
 * Opt-in live Jev smoke for the M7 review DecisionSpecs. Runs ONLY when
 * TYPESAFE_API_KEY is present; normal verification never needs network access or
 * credentials. Synthetic fixture text only. Engineering observation, NOT calibration.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class M7JevLiveSmokeTest {

    private static final String API_KEY = System.getenv("TYPESAFE_API_KEY");

    private JevProvider provider() {
        return new JevProvider(
                new JevProperties("https://api.typesafe.ai", API_KEY, "jev-latest",
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ObjectMapper());
    }

    @Test
    void policyComparisonOnSyntheticUncappedClause() {
        var state = new PolicyComparisonSpecs.PolicyComparisonState(
                1, "MSA", "Customer", "Limitation of Liability",
                "The liability of Supplier under this Agreement is uncapped and unlimited, "
                        + "however arising.",
                java.util.List.of());
        observe("uncapped", state, position(
                "Total liability should not exceed the fees paid in the preceding 12 months.",
                "Uncapped or unlimited liability is prohibited."));
    }

    @Test
    void policyComparisonOnSyntheticWithinFallbackClause() {
        var state = new PolicyComparisonSpecs.PolicyComparisonState(
                1, "MSA", "Customer", "Limitation of Liability",
                "Supplier's total aggregate liability arising out of or related to this "
                        + "Agreement shall not exceed two times the fees paid in the "
                        + "preceding 12 months.",
                java.util.List.of());
        observe("within_fallback", state, position(
                "Total liability should not exceed the fees paid in the preceding 12 months.",
                "Uncapped or unlimited liability is prohibited."));
    }

    private static Map<String, com.rivell.workbench.intelligence.SemanticQuestion> position(
            String preferred, String walkAway) {
        var questions = new java.util.LinkedHashMap<
                String, com.rivell.workbench.intelligence.SemanticQuestion>();
        questions.put("position_1", PolicyComparisonSpecs.positionQuestion(
                "Compare the clause with our playbook position on Limitation of Liability.\n"
                        + "Preferred: " + preferred + "\nWalk-away: " + walkAway));
        return questions;
    }

    private void observe(
            String label, PolicyComparisonSpecs.PolicyComparisonState state,
            Map<String, com.rivell.workbench.intelligence.SemanticQuestion> questions) {
        var spec = PolicyComparisonSpecs.spec(questions);
        ProviderExecutionResult result = provider().execute(new DecisionExecutionRequest(
                "jev-latest", state, spec.pack()));

        var answer = (ChoiceAnswer) result.answers().get("position_1");
        System.out.println("LIVE-JEV-M7 policy-comparison label=" + label
                + " resolvedModel=" + result.resolvedModel()
                + " usage=" + result.usage()
                + " selected=" + answer.selected()
                + " confidence=" + answer.confidence()
                + " probabilities=" + answer.probabilities());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
        assertThat(answer.selected()).isIn(PolicyComparisonSpecs.OPTIONS.keySet());
    }

    @Test
    void carveOutConsistencyOnSyntheticContradiction() {
        var state = new M7DecisionSpecs.CarveOutConsistencyState(
                1,
                "Supplier's aggregate liability shall not exceed two times the fees paid in "
                        + "the preceding 12 months, and this limitation applies to all "
                        + "obligations under this Agreement, including indemnification.",
                "Supplier shall indemnify and defend Customer against third-party claims "
                        + "without regard to any limitation of liability in this Agreement.");

        ProviderExecutionResult result = provider().execute(new DecisionExecutionRequest(
                "jev-latest", state, M7DecisionSpecs.carveOutConsistency().pack()));

        var answer = (ChoiceAnswer) result.answers().get(M7DecisionSpecs.CONSISTENCY);
        System.out.println("LIVE-JEV-M7 carve-out contradiction"
                + " resolvedModel=" + result.resolvedModel()
                + " usage=" + result.usage()
                + " selected=" + answer.selected()
                + " confidence=" + answer.confidence()
                + " probabilities=" + answer.probabilities());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
        assertThat(answer.selected()).isIn(M7DecisionSpecs.CONSISTENT,
                M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                M7DecisionSpecs.NOT_COMPARABLE_CARVE);
    }

    @Test
    void findingVerificationOnSyntheticCandidate() {
        var state = new M7DecisionSpecs.FindingVerificationState(
                M7DecisionSpecs.FINDING_VERIFICATION_STATE_SCHEMA,
                "We are the customer. The other party supplies the goods, services or software.",
                "Total liability should not exceed the fees paid in the preceding 12 months.",
                "Supplier's total aggregate liability shall not exceed the fees paid in the "
                        + "twelve (12) months preceding the claim.",
                java.util.List.of("The liability of Supplier under this Agreement is uncapped and "
                        + "unlimited, however arising."),
                java.util.List.of("Supplier's total aggregate liability shall not exceed the fees "
                        + "paid in the twelve (12) months preceding the claim."));

        ProviderExecutionResult result = provider().execute(new DecisionExecutionRequest(
                "jev-latest", state, M7DecisionSpecs.findingVerification().pack()));

        var satisfies = (YesNoAnswer) result.answers().get(M7DecisionSpecs.SATISFIES_POLICY);
        var newRisk = (YesNoAnswer) result.answers().get(M7DecisionSpecs.ADDITION_HURTS_US);
        System.out.println("LIVE-JEV-M7 verification"
                + " resolvedModel=" + result.resolvedModel()
                + " usage=" + result.usage()
                + " satisfiesPolicy=" + satisfies.probability()
                + " introducedNewRisk=" + newRisk.probability());

        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
        assertThat(satisfies.probability()).isBetween(0.0, 1.0);
        assertThat(newRisk.probability()).isBetween(0.0, 1.0);
    }
}
