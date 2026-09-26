package com.rivell.workbench.redline;

/** Verified redline artifact bytes opened for download. */
public record RedlineArtifactDownload(String filename, String contentType, byte[] content) {
}
