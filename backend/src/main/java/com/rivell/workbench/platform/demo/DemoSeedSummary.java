package com.rivell.workbench.platform.demo;

/** Counts and timing for one demo seed run; logged and asserted by the catalog test. */
record DemoSeedSummary(
        int contracts,
        int reviews,
        int findings,
        int knowledgeSources,
        int publications,
        boolean escalationTriggerPublished,
        long runtimeMillis) {
}
