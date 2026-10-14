package com.rivell.workbench.contracts.api;

import java.util.List;

/**
 * Bounded contract list. {@code nextCursor} is the opaque position to resume from and is
 * non-null exactly when more contracts exist.
 */
public record ContractListResponse(
        List<ContractListItemResponse> items, String nextCursor) {
}
