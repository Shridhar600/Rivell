package com.rivell.workbench.review.check;

import com.rivell.workbench.review.CheckMechanism;

/** Strategy for one plan-check mechanism. Implementations are pure domain logic. */
public interface ReviewCheckExecutor {

    CheckMechanism mechanism();

    CheckOutcome execute(ReviewCheckContext context);
}
