package com.rivell.workbench.workflow;

import java.util.UUID;

/** An approved change would replace or delete a clause a tracked change cannot represent. */
public class UntrackableChangeTargetException extends RuntimeException {

    public UntrackableChangeTargetException(UUID findingId) {
        super("This finding targets a schedule or a clause that holds a table. Rivell cannot "
                + "apply tracked changes there. Reject the finding, then edit the clause by hand "
                + "in the redline.");
    }
}
