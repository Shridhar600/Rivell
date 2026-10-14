package com.rivell.workbench.platform.docx;

/**
 * A package exceeded the permitted uncompressed size and was never handed to docx4j.
 *
 * <p>Distinct from a load failure and from a round-trip mismatch: nothing was verified because
 * nothing was loaded, and reporting it as a verification failure would send an operator looking for a
 * document defect that does not exist.
 */
public class PackageExpansionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PackageExpansionRejectedException() {
        super("The package expands beyond the permitted size for verification");
    }
}
