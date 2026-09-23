package com.rivell.workbench.intelligence;

/**
 * Provider-neutral bounded semantic question types. Each maps to one provider primitive
 * (Noul/Choice/Score for TypeSafe); no provider vocabulary leaks into these types.
 */
public sealed interface SemanticQuestion permits YesNoQuestion, ChoiceQuestion, RatingQuestion {
}
