package com.rivell.workbench.redline;

import java.util.List;
import java.util.Objects;

/**
 * Round-trip verification report. {@code passed} is true only when every layer passed;
 * a failed report never publishes an artifact.
 */
public record VerificationReport(
        boolean passed,
        RedlineFailureCode failureCode,
        String failureDetail,
        List<ChangeVerification> changes) {

    public VerificationReport {
        changes = changes == null ? List.of() : List.copyOf(changes);
        if (passed && failureCode != null) {
            throw new IllegalArgumentException("a passing report carries no failure code");
        }
        if (!passed) {
            Objects.requireNonNull(failureCode, "failureCode");
        }
    }

    public static VerificationReport verified(List<ChangeVerification> changes) {
        return new VerificationReport(true, null, null, changes);
    }

    public static VerificationReport failed(
            RedlineFailureCode failureCode, String failureDetail,
            List<ChangeVerification> changes) {
        return new VerificationReport(false, failureCode, failureDetail, changes);
    }
}
