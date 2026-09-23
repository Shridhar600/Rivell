package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human bulk-approval outcome: ids approved under the server-determined eligibility
 * rules, and skipped ids with the blocking reason.
 */
public record BulkApprovalView(List<UUID> approvedIds, Map<UUID, String> skipped) {
}
