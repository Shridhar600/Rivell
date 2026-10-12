package com.rivell.workbench.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Engine v2 settings (spec §5, §6). {@code engine}: the engine new reviews are frozen on (default
 * V1). {@code routing}: a judged position that addresses the clause gets a second opinion when its
 * flag mass is in [adjudicateFrom, adjudicateBelow) — P0 measured 0.4 to 0.8, about 4.4 per
 * contract on dev and dev2 — and at most {@code maxAdjudications} second opinions run per
 * analysis run; the rest go to the user.
 */
@ConfigurationProperties("rivell.review")
public record ReviewEngineProperties(ReviewEngine engine, Routing routing) {

    public ReviewEngineProperties {
        engine = engine == null ? ReviewEngine.V1 : engine;
        routing = routing == null ? new Routing(null, null, null) : routing;
    }

    public record Routing(Double adjudicateFrom, Double adjudicateBelow, Integer maxAdjudications) {

        public Routing {
            adjudicateFrom = adjudicateFrom == null ? 0.4 : adjudicateFrom;
            adjudicateBelow = adjudicateBelow == null ? 0.8 : adjudicateBelow;
            maxAdjudications = maxAdjudications == null ? 40 : maxAdjudications;
            if (adjudicateFrom < 0.0 || adjudicateBelow > 1.0 || adjudicateFrom >= adjudicateBelow) {
                throw new IllegalArgumentException(
                        "rivell.review.routing band must satisfy 0 <= adjudicate-from < adjudicate-below <= 1");
            }
            if (maxAdjudications < 0) {
                throw new IllegalArgumentException("rivell.review.routing.max-adjudications must be >= 0");
            }
        }
    }
}
