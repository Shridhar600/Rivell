package com.rivell.workbench.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * M6 retrieval decision specs: bounded, code-owned, versioned judgments for the
 * precedent lane only. One relevance judgment per precedent candidate (batched
 * questions over minimal state) and one policy-compatibility judgment when exactly
 * one applicable policy exists. Never a whole-corpus call; never a legal decision.
 */
final class M6SemanticSpecs {

    static final String PRECEDENT_RELEVANCE_SPEC = "precedent-relevance";
    static final String PRECEDENT_POLICY_COMPATIBILITY_SPEC = "precedent-policy-compatibility";

    static final String SAME_LEGAL_ISSUE = "same_legal_issue";
    static final String SAME_CLAUSE_FUNCTION = "same_clause_function";
    static final String COMPARABLE_CONTEXT = "comparable_context";
    static final String COMPATIBLE_WITH_POLICY = "compatible_with_policy";

    private static final int VERSION = 1;

    private M6SemanticSpecs() {
    }

    static DecisionSpec precedentRelevance() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        questions.put(SAME_LEGAL_ISSUE, new YesNoQuestion(
                "Does this historical clause address the same legal issue as the reviewed clause?",
                "The clause addresses the same legal issue (such as limitation of liability, "
                        + "indemnification, confidentiality).",
                "The clause addresses a different legal issue."));
        questions.put(SAME_CLAUSE_FUNCTION, new YesNoQuestion(
                "Does this historical clause serve the same clause function?",
                "The clause performs the same contractual function (allocation of risk, "
                        + "remedy, obligation scope).",
                "The clause performs a different contractual function."));
        questions.put(COMPARABLE_CONTEXT, new YesNoQuestion(
                "Does this historical clause come from a materially comparable contract context?",
                "The contract context (type, relationship, subject matter) is materially comparable.",
                "The contract context is materially different, making the clause weak evidence."));
        return new DecisionSpec(
                PRECEDENT_RELEVANCE_SPEC, VERSION,
                "Assess whether one historical precedent clause is materially relevant to the "
                        + "reviewed clause of the same legal concept.",
                M6SemanticStates.PrecedentRelevanceState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    static DecisionSpec precedentPolicyCompatibility() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        questions.put(COMPATIBLE_WITH_POLICY, new YesNoQuestion(
                "Is this historical clause compatible with the organization's currently "
                        + "applicable policy position?",
                "The clause does not contradict the applicable policy position.",
                "The clause conflicts with the applicable policy position (for example a "
                        + "prohibited position)."));
        return new DecisionSpec(
                PRECEDENT_POLICY_COMPATIBILITY_SPEC, VERSION,
                "Assess whether one historical precedent clause is compatible with the "
                        + "applicable published organizational policy.",
                M6SemanticStates.PolicyCompatibilityState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }
}
