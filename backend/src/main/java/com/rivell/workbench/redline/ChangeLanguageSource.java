package com.rivell.workbench.redline;

/** Where an approved change's language came from; NONE for deletions. */
public enum ChangeLanguageSource {
    APPROVED_DRAFTING,
    GENERATIVE,
    HUMAN_EDIT,
    NONE
}
