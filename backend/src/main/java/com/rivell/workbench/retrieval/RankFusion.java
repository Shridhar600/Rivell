package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic rank fusion for candidate-generation lanes (Architecture Decision
 * #8.2). Raw per-lane scores (FTS rank, vector distance) are never mixed; only ranks
 * enter the fusion (Reciprocal Rank Fusion, k = 60). Ties break by stable candidate
 * id ordering, so identical inputs always produce the same fused order.
 */
public final class RankFusion {

    public static final int RRF_K = 60;

    private RankFusion() {
    }

    public record LaneHit(UUID candidateId, int rank) {
    }

    public record FusedHit(
            UUID candidateId,
            double score,
            int fusedRank,
            List<String> lanes,
            List<Integer> laneRanks) {

        public FusedHit {
            lanes = lanes == null ? List.of() : List.copyOf(lanes);
            laneRanks = laneRanks == null ? List.of() : List.copyOf(laneRanks);
        }
    }

    /**
     * Fuses per-lane rank lists into one deterministic ordering. A candidate absent
     * from a lane contributes nothing for that lane — lanes never fabricate ranks.
     */
    public static List<FusedHit> fuse(Map<String, List<LaneHit>> lanes) {
        var lanesPerCandidate = new LinkedHashMap<UUID, LinkedHashSet<String>>();
        var ranksPerCandidate = new LinkedHashMap<UUID, List<Integer>>();
        var totals = new LinkedHashMap<UUID, Double>();
        for (var entry : lanes.entrySet()) {
            for (var hit : entry.getValue()) {
                var candidateId = hit.candidateId();
                lanesPerCandidate.computeIfAbsent(candidateId, key -> new LinkedHashSet<>())
                        .add(entry.getKey());
                ranksPerCandidate.computeIfAbsent(candidateId, key -> new ArrayList<>())
                        .add(hit.rank());
                var current = totals.getOrDefault(candidateId, 0.0);
                totals.put(candidateId, current + 1.0 / (RRF_K + hit.rank()));
            }
        }
        var ordered = new ArrayList<FusedHit>();
        for (var entry : totals.entrySet()) {
            ordered.add(new FusedHit(
                    entry.getKey(), entry.getValue(), 0,
                    List.copyOf(lanesPerCandidate.get(entry.getKey())),
                    List.copyOf(ranksPerCandidate.get(entry.getKey()))));
        }
        ordered.sort(Comparator
                .comparingDouble(FusedHit::score).reversed()
                .thenComparing(FusedHit::candidateId));
        var fused = new ArrayList<FusedHit>();
        for (int i = 0; i < ordered.size(); i++) {
            var hit = ordered.get(i);
            fused.add(new FusedHit(
                    hit.candidateId(), hit.score(), i + 1, hit.lanes(), hit.laneRanks()));
        }
        return List.copyOf(fused);
    }
}
