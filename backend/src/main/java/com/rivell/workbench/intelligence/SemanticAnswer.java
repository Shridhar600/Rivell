package com.rivell.workbench.intelligence;

/**
 * Provider-neutral bounded answer types. Mirrors provider facts honestly:
 * yes/no answers carry the probability only; choice/rating answers carry the
 * provider-computed confidence plus the full probability distribution.
 */
public sealed interface SemanticAnswer permits YesNoAnswer, ChoiceAnswer, RatingAnswer {
}
