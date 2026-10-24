package com.rivell.workbench.review;

/**
 * The fit check's verdict as a finding records it: FITS, DOES_NOT_FIT or UNKNOWN. The strings are
 * what the drafting payload, the finding quality, the diagnostics view and the eval read, so they
 * are owned here rather than repeated at each boundary.
 */
public final class FitVerdicts {

    public static final String FITS = "FITS";
    public static final String DOES_NOT_FIT = "DOES_NOT_FIT";
    public static final String UNKNOWN = "UNKNOWN";

    private FitVerdicts() {
    }
}
