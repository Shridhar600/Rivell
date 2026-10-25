package com.rivell.workbench.intelligence;

/**
 * The provider account has no credit or has reached a spend limit. Waiting cannot help, so this
 * is rejected rather than retried and has its own reviewer-facing explanation.
 */
public class ProviderOutOfCreditException extends ProviderRejectedException {

    public ProviderOutOfCreditException(String message) {
        super(message);
    }
}
