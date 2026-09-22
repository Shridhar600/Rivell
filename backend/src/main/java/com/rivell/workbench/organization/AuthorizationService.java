package com.rivell.workbench.organization;

public interface AuthorizationService {

    boolean isAuthorized(UserIdentity identity, Permission permission);
}
