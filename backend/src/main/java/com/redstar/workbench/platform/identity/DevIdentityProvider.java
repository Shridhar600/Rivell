package com.redstar.workbench.platform.identity;

import com.redstar.workbench.organization.IdentityProvider;
import com.redstar.workbench.organization.UserIdentity;

public final class DevIdentityProvider implements IdentityProvider {

    private final UserIdentity identity;

    public DevIdentityProvider(DevIdentityProperties properties) {
        this.identity = new UserIdentity(properties.userId(), properties.organizationId(), properties.roles());
    }

    @Override
    public UserIdentity currentIdentity() {
        return identity;
    }
}
