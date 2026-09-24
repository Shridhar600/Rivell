package com.rivell.workbench.retrieval;

/**
 * Per-lane ranking information carried by an evidence item (AD #8.10). Structured
 * lanes carry none; hybrid lanes carry the rank at which the item was produced
 * (1-based) and the fused position after rank fusion.
 */
public record LaneInfo(Integer lexicalRank, Integer vectorRank, Integer fusedRank,
        Double fusedScore) {

    public static final LaneInfo NONE = new LaneInfo(null, null, null, null);
}
