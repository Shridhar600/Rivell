package com.rivell.workbench.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * The omissions lane's contract search (engine v2 P4, design D5): the clauses of one
 * contract ranked for a position's text by shared words and by embedding similarity,
 * fused by rank. Ranking only: the caller's comparison decides whether a clause
 * addresses the position. A database or a dedicated vector store can replace the
 * implementation behind this interface (DW-064).
 */
public interface ContractClauseSearch {

    record Clause(UUID headingNodeId, String text) {
    }

    record Hit(UUID headingNodeId, int fusedRank, Integer lexicalRank, Integer vectorRank) {
    }

    /** {@code hits.get(i)} = clauses ranked for {@code queries.get(i)}, at most limit each. */
    record Result(List<List<Hit>> hits, List<String> degradation) {
    }

    /**
     * Clauses of one contract ranked for each query: shared words + embedding cosine,
     * fused (RRF); the vector lane is skipped when embeddings are off or fail (the
     * degradation says which).
     */
    Result search(List<String> queries, List<Clause> clauses, int limit);
}
