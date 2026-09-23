package com.rivell.workbench.intelligence;

/**
 * Centralized routing policy: maps a decision spec to the provider and model alias that
 * execute it. Deliberately simple; task-specific thresholds live with the calling domain,
 * never here.
 */
public interface ModelPolicy {

    ModelBinding resolve(DecisionSpec spec);
}
