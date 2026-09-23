package com.rivell.workbench.knowledge;

/**
 * Independent verification state of an interpreted field. NOT_VERIFIED is the honest
 * default; DISPUTED means the independent source check contradicts the proposal.
 */
public enum VerificationState {
    NOT_VERIFIED,
    VERIFIED,
    UNCERTAIN,
    DISPUTED
}
