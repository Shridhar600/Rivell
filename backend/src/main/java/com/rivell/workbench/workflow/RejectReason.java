package com.rivell.workbench.workflow;

/** Why a human rejected a machine finding. */
public enum RejectReason {
    /** The clause is acceptable as written, so no change goes into the redline. */
    KEEP_AS_DRAFTED,
    /** Rivell was wrong about the finding. */
    FINDING_WRONG
}
