package com.rivell.workbench.foundation;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic sanitization of untrusted client filenames. Strips path components and
 * control characters, enforces a non-blank base name, and validates the extension against
 * the caller's controlled allowlist.
 */
public final class FilenameSanitizer {

    private FilenameSanitizer() {
    }

    public static String sanitize(String filename, Set<String> allowedExtensions) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("The file needs a name.");
        }
        var base = filename.trim();
        var slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        var cleaned = base.replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("The file needs a name.");
        }
        var lower = cleaned.toLowerCase(Locale.ROOT);
        if (allowedExtensions.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException(
                    "Upload " + acceptedFiles(allowedExtensions) + ".");
        }
        return cleaned;
    }

    private static String acceptedFiles(Set<String> allowedExtensions) {
        return allowedExtensions.stream().sorted().map(extension -> switch (extension) {
            case ".docx" -> "a Word file (.docx)";
            case ".xlsx" -> "an Excel file (.xlsx)";
            default -> "a " + extension + " file";
        }).collect(java.util.stream.Collectors.joining(" or "));
    }
}
