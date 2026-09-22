package com.rivell.workbench.platform.identity;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

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
