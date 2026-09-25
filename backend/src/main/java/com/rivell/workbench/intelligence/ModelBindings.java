package com.rivell.workbench.intelligence;

import java.util.Objects;

/**
 * Configured model bindings for the two M7 routing roles: bounded semantic decisions
 * (Jev) and open-ended generation. A review snapshot records this identity so an audit
 * can tell which policy configuration was in effect; concrete resolved models are
 * recorded per execution.
 */
public record ModelBindings(ModelBinding decisionProvider, ModelBinding generativeProvider) {

    public ModelBindings {
        Objects.requireNonNull(decisionProvider, "decisionProvider");
        Objects.requireNonNull(generativeProvider, "generativeProvider");
    }
}
