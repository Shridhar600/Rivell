package com.rivell.workbench.redline;

/**
 * Round-trip verifier port: independently reopens the generated DOCX and proves that
 * exactly the planned approved changes are represented. Implementations live in
 * platform (docx4j).
 */
public interface RedlineVerifier {

    VerificationReport verify(byte[] sourceBytes, byte[] generatedBytes,
            DocumentExecutionPlan plan);
}
