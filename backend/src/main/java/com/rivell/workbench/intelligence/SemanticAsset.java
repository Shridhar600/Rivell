package com.rivell.workbench.intelligence;

/**
 * A versioned semantic asset (bounded decision or bounded generative extraction)
 * that the centralized model policy can route.
 */
public interface SemanticAsset {

    String id();

    int version();

    String purpose();

    int stateSchemaVersion();
}
