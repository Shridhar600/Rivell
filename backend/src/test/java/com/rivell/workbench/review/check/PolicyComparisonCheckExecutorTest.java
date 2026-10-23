package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.EscalationPayload;

/**
 * An escalation signal must carry only what the policy said. A null approval is "not
 * specified", not a positive "not required".
 */
class PolicyComparisonCheckExecutorTest {

    @Test
    void aNullApprovalStaysNullInTheEscalationPayload() {
        var payload = new EscalationPayload("Escalate above net 90", null);

        assertThat(payload.requiresApproval()).isNull();
    }

    @Test
    void aNullApprovalIsOmittedFromTheQualityMap() {
        var quality = PolicyComparisonCheckExecutor.escalationQuality(
                "Escalate above net 90", null);

        assertThat(quality).doesNotContainKey("requiresApproval")
                .containsEntry("escalationTrigger", "Escalate above net 90");
    }

    @Test
    void aKnownApprovalIsWrittenToTheQualityMap() {
        assertThat(PolicyComparisonCheckExecutor.escalationQuality("Escalate", Boolean.TRUE))
                .containsEntry("requiresApproval", true);
        assertThat(PolicyComparisonCheckExecutor.escalationQuality("Escalate", Boolean.FALSE))
                .containsEntry("requiresApproval", false);
    }
}
