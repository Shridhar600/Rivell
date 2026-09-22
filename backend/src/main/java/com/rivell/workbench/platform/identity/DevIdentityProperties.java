package com.rivell.workbench.platform.identity;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rivell.identity.dev")
public record DevIdentityProperties(UUID userId, UUID organizationId, Set<String> roles) {
}
