package com.redstar.workbench.foundation;

import java.util.regex.Pattern;

public record StorageKey(String value) {

    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");

    public StorageKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        if (value.startsWith("/") || WINDOWS_DRIVE_PATH.matcher(value).matches() || value.contains("\\")) {
            throw new IllegalArgumentException("Storage key must be a forward-slash relative path");
        }
        for (var segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Storage key contains an unsafe path segment");
            }
        }
    }
}
