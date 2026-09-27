package com.rivell.workbench.contracts.api;

import com.rivell.workbench.contracts.EnrichmentFailureReason;

/**
 * The failure classification a tenant is allowed to see (M10A.1 / DW-041).
 *
 * <p>{@link EnrichmentFailureReason} is an operator-facing diagnosis: it distinguishes a missing
 * credential from a refused request from a timeout, because that is exactly what an operator needs
 * in order to act. None of that is the tenant's business. Telling a tenant that the deployment has
 * no credentials configured, or that a provider refused on quota, discloses the operator's
 * configuration and account state, and it implies a cause the tenant cannot act on. The locked
 * architecture is explicit on both counts: "Technical model/retrieval internals remain outside the
 * lawyer-facing UI", and "No retrieval/model context may cross tenant boundaries."
 *
 * <p>So the precise reason is still recorded in state and stays queryable by an operator; only the
 * projection onto this narrower vocabulary crosses the API boundary. The tenant keeps what it
 * actually needs: whether the document is the problem, or the service is, and therefore whether a
 * retry is worthwhile.
 *
 * <p>The mapping is a switch with no default, so adding a reason to {@link EnrichmentFailureReason}
 * fails compilation until someone decides whether a tenant may see it. That is the point.
 */
public enum TenantEnrichmentFailureReason {

    /** The document cannot be enriched as supplied. The tenant can act: supply a usable document. */
    UNSUPPORTED_SOURCE,
    /**
     * Rivell could not complete the analysis. Deliberately says nothing about credentials, quota,
     * provider identity or timing: the tenant can act by retrying, and an operator can find the
     * cause in the recorded reason.
     */
    SERVICE_UNAVAILABLE,
    /** Anything else. The cause belongs in server logs and in state, not on this surface. */
    INTERNAL;

    public static TenantEnrichmentFailureReason of(EnrichmentFailureReason reason) {
        return switch (reason) {
            case NO_PROVIDER_CONFIGURED, PROVIDER_TIMEOUT, PROVIDER_UNAVAILABLE,
                    PROVIDER_REJECTED, PROVIDER_PROTOCOL -> SERVICE_UNAVAILABLE;
            case UNSUPPORTED_SOURCE -> UNSUPPORTED_SOURCE;
            case INTERNAL -> INTERNAL;
        };
    }
}
