package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.SemanticDecisionException;

/**
 * In-memory contract clause search (engine v2 P4, design D5): a contract holds tens of
 * clauses, so a shared-word score plus cosine similarity over one batched embedding call
 * per side is enough. The word score is the share of the query's distinct words
 * (three letters or more, minus a tiny stoplist) the clause carries; a clause with no
 * shared word is not in the lexical lane. Ties keep clause order. The vector lane ranks
 * every clause when embeddings work, and is skipped (with a named degradation) when
 * embeddings are off or fail.
 */
public final class InMemoryContractClauseSearch implements ContractClauseSearch {

    private static final Set<String> STOPLIST = Set.of(
            "the", "and", "for", "any", "are", "not", "all", "this", "that", "with",
            "shall", "may", "such");

    private final EmbeddingService embeddingService;

    public InMemoryContractClauseSearch(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public Result search(List<String> queries, List<Clause> clauses, int limit) {
        var empty = new ArrayList<List<Hit>>();
        if (queries != null) {
            for (int index = 0; index < queries.size(); index++) {
                empty.add(List.of());
            }
        }
        if (queries == null || queries.isEmpty() || clauses == null || clauses.isEmpty()
                || limit <= 0) {
            return new Result(List.copyOf(empty), List.of());
        }
        var degradation = new ArrayList<String>();
        List<float[]> clauseVectors = null;
        List<float[]> queryVectors = null;
        if (embeddingService.isConfigured()) {
            clauseVectors = embedEach(clauses.stream().map(Clause::text).toList(), degradation);
            if (clauseVectors != null) {
                queryVectors = embedEach(queries, degradation);
            }
        } else {
            degradation.add("VECTOR_LANE_UNCONFIGURED");
        }
        var result = new ArrayList<List<Hit>>(queries.size());
        for (int index = 0; index < queries.size(); index++) {
            var lexical = lexicalLane(queries.get(index), clauses);
            var vector = queryVectors == null || queryVectors.get(index) == null
                    ? List.<RankFusion.LaneHit>of()
                    : vectorLane(queryVectors.get(index), clauseVectors, clauses);
            var lanes = new LinkedHashMap<String, List<RankFusion.LaneHit>>();
            lanes.put("lexical", lexical);
            lanes.put("vector", vector);
            result.add(hits(RankFusion.fuse(lanes), lexical, vector, limit));
        }
        return new Result(List.copyOf(result), List.copyOf(degradation));
    }

    /**
     * One batch for this side; if the provider rejects the batch, each text is retried alone so a
     * single bad input does not switch the vector lane off for every clause. A failed input keeps
     * a null slot (excluded from the lane) and the partial degradation is named. A timeout or an
     * outage turns the vector lane off at once: one call per text would spend the review's budget
     * on an endpoint that is not answering.
     */
    private List<float[]> embedEach(List<String> texts, List<String> degradation) {
        try {
            return embeddingService.embed(texts).vectors();
        } catch (SemanticDecisionException batchFailure) {
            if (ProviderFailures.isOutOfCredit(batchFailure)
                    || !(batchFailure instanceof ProviderRejectedException)) {
                degradation.add("VECTOR_LANE_UNAVAILABLE");
                return null;
            }
            var vectors = new ArrayList<float[]>(texts.size());
            var failed = false;
            for (var text : texts) {
                try {
                    vectors.add(embeddingService.embed(List.of(text)).vectors().get(0));
                } catch (SemanticDecisionException e) {
                    if (ProviderFailures.isOutOfCredit(e)) {
                        degradation.add("VECTOR_LANE_UNAVAILABLE");
                        return null;
                    }
                    vectors.add(null);
                    failed = true;
                }
            }
            if (failed) {
                var any = vectors.stream().anyMatch(java.util.Objects::nonNull);
                degradation.add(any ? "VECTOR_LANE_PARTIAL" : "VECTOR_LANE_UNAVAILABLE");
                return any ? java.util.Collections.unmodifiableList(vectors) : null;
            }
            return List.copyOf(vectors);
        }
    }

    /** Ranks the clauses that share at least one word with the query; ties keep clause order. */
    private static List<RankFusion.LaneHit> lexicalLane(String query, List<Clause> clauses) {
        var queryWords = words(query);
        if (queryWords.isEmpty()) {
            return List.of();
        }
        var scored = new ArrayList<Scored>();
        for (int index = 0; index < clauses.size(); index++) {
            var clauseWords = words(clauses.get(index).text());
            var shared = queryWords.stream().filter(clauseWords::contains).count();
            if (shared > 0) {
                scored.add(new Scored(index, (double) shared / queryWords.size()));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return laneHits(scored, clauses);
    }

    /** Every clause ranked by cosine similarity; ties keep clause order. */
    private static List<RankFusion.LaneHit> vectorLane(
            float[] query, List<float[]> vectors, List<Clause> clauses) {
        var scored = new ArrayList<Scored>();
        for (int index = 0; index < clauses.size(); index++) {
            if (vectors.get(index) != null) {
                scored.add(new Scored(index, cosine(query, vectors.get(index))));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return laneHits(scored, clauses);
    }

    /** A stable sort keeps equal scores in clause order. */
    private static List<RankFusion.LaneHit> laneHits(List<Scored> scored, List<Clause> clauses) {
        var hits = new ArrayList<RankFusion.LaneHit>();
        for (int rank = 0; rank < scored.size(); rank++) {
            hits.add(new RankFusion.LaneHit(
                    clauses.get(scored.get(rank).index()).headingNodeId(), rank + 1));
        }
        return List.copyOf(hits);
    }

    private static List<Hit> hits(
            List<RankFusion.FusedHit> fused, List<RankFusion.LaneHit> lexical,
            List<RankFusion.LaneHit> vector, int limit) {
        var hits = new ArrayList<Hit>();
        for (var hit : fused) {
            if (hits.size() >= limit) {
                break;
            }
            hits.add(new Hit(hit.candidateId(), hit.fusedRank(),
                    rankOf(lexical, hit.candidateId()), rankOf(vector, hit.candidateId())));
        }
        return List.copyOf(hits);
    }

    private static Integer rankOf(List<RankFusion.LaneHit> hits, UUID candidateId) {
        for (var hit : hits) {
            if (hit.candidateId().equals(candidateId)) {
                return hit.rank();
            }
        }
        return null;
    }

    private static Set<String> words(String text) {
        var words = new LinkedHashSet<String>();
        if (text == null) {
            return words;
        }
        for (var word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() >= 3 && !STOPLIST.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record Scored(int index, double score) {
    }
}
