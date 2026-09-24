package com.rivell.workbench.retrieval;

/**
 * pgvector text-literal formatting for stored/queried vectors:
 * {@code '[1.0,2.0,0.5]'} (dimensionless column, model-identity filtered queries).
 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String of(float[] vector) {
        var literal = new StringBuilder(vector.length * 8 + 2);
        literal.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
