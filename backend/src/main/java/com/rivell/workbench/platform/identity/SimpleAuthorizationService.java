package com.rivell.workbench.platform.identity;

import java.util.Objects;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.Permission;
import com.rivell.workbench.organization.UserIdentity;

public final class SimpleAuthorizationService implements AuthorizationService {

    @Override
    public boolean isAuthorized(UserIdentity identity, Permission permission) {
        Objects.requireNonNull(permission, "permission");
        return Objects.requireNonNull(identity, "identity").roles().contains("ADMIN");
    }
}
