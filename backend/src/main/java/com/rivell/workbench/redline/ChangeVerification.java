package com.rivell.workbench.redline;

import java.util.UUID;

/** Per-change round-trip verification result. */
public record ChangeVerification(
        UUID approvedChangeId,
        RedlineVerificationStatus status,
        String detail) {
}
