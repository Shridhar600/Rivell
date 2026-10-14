package com.rivell.workbench.workflow;

import java.util.UUID;

/** An approved change would replace or delete a clause a tracked change cannot represent. */
public class UntrackableChangeTargetException extends RuntimeException {

    public UntrackableChangeTargetException(UUID findingId) {
        super("Finding " + findingId + " targets a schedule or a clause that holds a table; "
                + "Rivell cannot apply a tracked change there. Reject the finding and edit the "
                + "clause by hand in the redline.");
    }
}
