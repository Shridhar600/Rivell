package com.rivell.workbench.taxonomy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rivell.workbench.intelligence.DecisionExecutionMetadata;

/**
 * Topics a section expresses. {@code topics} are every leaf at or above the threshold,
 * most probable first; empty means the section matches no topic ("none" is an answer).
 * {@code primary} is null when the section has no topics or no clear main subject.
 * {@code headingTopicKey} is the single passed topic the heading names, kept apart from the
 * primary so only presence coverage reads it. {@code executions} records every model call
 * that produced this result.
 */
public record TopicClassification(
        List<ConceptKey> topics,
        ConceptKey primary,
        ConceptKey headingTopicKey,
        Map<ConceptKey, Double> probabilities,
        int taxonomyVersion,
        List<DecisionExecutionMetadata> executions,
        int questions) {

    public TopicClassification {
        topics = List.copyOf(topics);
        probabilities = Map.copyOf(probabilities);
        executions = List.copyOf(executions);
        if (primary != null && !topics.contains(primary)) {
            throw new IllegalArgumentException("Primary topic " + primary + " is not among the topics");
        }
        if (headingTopicKey != null && !topics.contains(headingTopicKey)) {
            throw new IllegalArgumentException(
                    "Heading topic " + headingTopicKey + " is not among the topics");
        }
    }

    public int decisionExecutions() {
        return executions.size();
    }

    public Optional<ConceptKey> primaryTopic() {
        return Optional.ofNullable(primary);
    }

    public Optional<ConceptKey> headingTopic() {
        return Optional.ofNullable(headingTopicKey);
    }

    /** Topics other than the primary; all topics when there is no primary. */
    public List<ConceptKey> secondaryTopics() {
        return topics.stream().filter(topic -> !topic.equals(primary)).toList();
    }
}
