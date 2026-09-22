package com.redstar.workbench.organization;

public interface AuthorizationService {

    boolean isAuthorized(UserIdentity identity, Permission permission);
}
