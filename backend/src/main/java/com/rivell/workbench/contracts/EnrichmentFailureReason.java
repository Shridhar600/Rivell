package com.rivell.workbench.contracts;

/**
 * Why a contract semantic enrichment run failed. Code-owned and structural: it tells an operator
 * and a client whether the failure is worth retrying, and it never contains document or provider
 * text. A FAILED enrichment without one of these reasons is not diagnosable.
 */
public enum EnrichmentFailureReason {

    /** The configured provider is not usable at all (no credentials, no configured adapter). */
    NO_PROVIDER_CONFIGURED,
    /** The provider was unreachable or returned a retryable server-side failure. */
    PROVIDER_UNAVAILABLE,
    /** The provider did not answer within the configured read timeout. */
    PROVIDER_TIMEOUT,
    /** The provider refused the request (auth, quota, or request rejected as invalid). */
    PROVIDER_REJECTED,
    /** The provider answered, but not in the shape the decision contract requires. */
    PROVIDER_PROTOCOL,
    /** The document cannot be enriched as supplied. */
    UNSUPPORTED_SOURCE,
    /** Anything else. Deliberately coarse: the detail belongs in server logs, not in state. */
    INTERNAL
}
