package com.rivell.workbench.taxonomy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Topics a section expresses. {@code topics} are every leaf at or above the threshold,
 * most probable first; empty means the section matches no topic ("none" is an answer).
 * {@code primary} is null when the section has no topics or no clear main subject.
 */
public record TopicClassification(
        List<ConceptKey> topics,
        ConceptKey primary,
        Map<ConceptKey, Double> probabilities,
        int taxonomyVersion,
        int decisionExecutions,
        int questions) {

    public TopicClassification {
        topics = List.copyOf(topics);
        probabilities = Map.copyOf(probabilities);
        if (primary != null && !topics.contains(primary)) {
            throw new IllegalArgumentException("Primary topic " + primary + " is not among the topics");
        }
    }

    public Optional<ConceptKey> primaryTopic() {
        return Optional.ofNullable(primary);
    }

    /** Topics other than the primary; all topics when there is no primary. */
    public List<ConceptKey> secondaryTopics() {
        return topics.stream().filter(topic -> !topic.equals(primary)).toList();
    }
}
