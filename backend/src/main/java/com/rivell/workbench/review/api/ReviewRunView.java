package com.rivell.workbench.review.api;

import java.util.UUID;

/** Identity of one analysis run (generation). */
public record ReviewRunView(UUID id, int runNumber, String status) {
}
