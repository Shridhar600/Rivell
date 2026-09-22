package com.redstar.workbench.organization;

public final class Permissions {

    public static final Permission CONTRACT_UPLOAD = new Permission("contracts:upload");
    public static final Permission CONTRACT_READ = new Permission("contracts:read");

    private Permissions() {
    }
}
