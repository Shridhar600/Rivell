package com.rivell.workbench.workflow.api;

import java.util.UUID;

/** The finding the user added; read it through the review's findings like any other. */
public record AddedFindingView(UUID findingId) {
}
