package com.rivell.workbench.organization;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserIdentity(UUID userId, UUID organizationId, Set<String> roles) {

    public UserIdentity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
