package com.rivell.workbench.review;

import java.util.UUID;

public class FindingNotFoundException extends RuntimeException {

    public FindingNotFoundException(UUID findingId) {
        super("Finding not found: " + findingId);
    }
}
