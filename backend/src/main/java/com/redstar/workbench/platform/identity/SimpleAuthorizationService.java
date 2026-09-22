package com.redstar.workbench.platform.identity;

import java.util.Objects;

import com.redstar.workbench.organization.AuthorizationService;
import com.redstar.workbench.organization.Permission;
import com.redstar.workbench.organization.UserIdentity;

public final class SimpleAuthorizationService implements AuthorizationService {

    @Override
    public boolean isAuthorized(UserIdentity identity, Permission permission) {
        Objects.requireNonNull(permission, "permission");
        return Objects.requireNonNull(identity, "identity").roles().contains("ADMIN");
    }
}
