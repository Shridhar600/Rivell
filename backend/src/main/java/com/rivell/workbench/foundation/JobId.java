package com.rivell.workbench.foundation;

import java.util.Objects;
import java.util.UUID;

public record JobId(UUID value) {

    public JobId {
        Objects.requireNonNull(value, "value");
    }
}
