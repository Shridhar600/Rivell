package com.redstar.workbench.organization;

public record Permission(String key) {

    public Permission {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Permission key must not be blank");
        }
    }
}
