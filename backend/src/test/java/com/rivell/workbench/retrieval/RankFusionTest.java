package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Pure-algorithm tests for the deterministic rank fusion: RRF math, tie-breaking,
 * and reproducibility for identical inputs.
 */
class RankFusionTest {

    @Test
    void fusedScoreIsTheReciprocalRankSum() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var fused = RankFusion.fuse(Map.of(
                "lexical", List.of(new RankFusion.LaneHit(a, 1)),
                "vector", List.of(new RankFusion.LaneHit(b, 1))));

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61);
        assertThat(fused.get(0).fusedRank()).isEqualTo(1);
        assertThat(fused.get(1).score()).isEqualTo(1.0 / 61);
        // tie broken by stable candidate id ordering
        var first = fused.get(0).candidateId();
        var second = fused.get(1).candidateId();
        assertThat(first.compareTo(second)).isLessThan(0);
    }

    @Test
    void candidatePresentInBothLanesOutranksSingleLanePresence() {
        var both = UUID.randomUUID();
        var lexicalOnly = UUID.randomUUID();
        var lanes = new LinkedHashMap<String, List<RankFusion.LaneHit>>();
        lanes.put("lexical", List.of(new RankFusion.LaneHit(both, 2),
                new RankFusion.LaneHit(lexicalOnly, 1)));
        lanes.put("vector", List.of(new RankFusion.LaneHit(both, 1)));
        var fused = RankFusion.fuse(lanes);

        assertThat(fused.get(0).candidateId()).isEqualTo(both);
        assertThat(fused.get(0).lanes()).containsExactly("lexical", "vector");
        assertThat(fused.get(0).laneRanks()).containsExactly(2, 1);
        assertThat(fused.get(1).candidateId()).isEqualTo(lexicalOnly);
    }

    @Test
    void fusionIsReproducibleForIdenticalInputs() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var lanes = new LinkedHashMap<String, java.util.List<RankFusion.LaneHit>>();
        lanes.put("lexical", List.of(new RankFusion.LaneHit(a, 1), new RankFusion.LaneHit(b, 2)));
        lanes.put("vector", List.of(new RankFusion.LaneHit(c, 1), new RankFusion.LaneHit(a, 2)));

        var first = RankFusion.fuse(lanes);
        var second = RankFusion.fuse(lanes);
        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(RankFusion.FusedHit::candidateId)
                .containsExactly(a, c, b);
    }
}
