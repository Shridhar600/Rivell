package com.rivell.workbench.workflow.api;

import java.util.List;

/** Bounded decision list (current effective decisions or one finding's history). */
public record DecisionListResponse(List<DecisionView> items, boolean truncated) {
}
