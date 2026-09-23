package com.rivell.workbench.intelligence;

/**
 * Minimal structured state for a semantic decision. Payload is a typed, Jackson-serializable
 * value owned by the calling domain module; telemetry records the schema version, never the payload.
 */
public interface SemanticState {

    int schemaVersion();

    Object payload();
}
