package com.rivell.workbench.platform.demo;

/** One named, deterministic local demo state produced only through real services. */
public record DemoScenario(
        String key,
        String displayName,
        Kind kind,
        String fixture,
        String intendedState,
        String capabilities,
        String caveats) {

    public enum Kind {
        REVIEW, CONTRACT, KNOWLEDGE
    }
}
