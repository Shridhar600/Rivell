package com.rivell.workbench.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Contract(UUID id, UUID organizationId, String title, Instant createdAt) {

    public Contract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (title != null && title.isBlank()) {
            title = null;
        }
    }
}
