package com.rivell.workbench.platform.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Bounds decompression of untrusted DOCX input before docx4j loads it, and counts the true
 * top-level body children so content dropped by the loader becomes visible.
 */
public final class ZipGuard {

    public record ScanResult(long totalUncompressedBytes, byte[] mainDocumentXml) {
    }

    private ZipGuard() {
    }

    /** Verifies the size bound by draining every entry through a counting read; nothing is kept. */
    public static void verify(byte[] docxBytes, long maxUncompressedBytes) throws IOException {
        long total = 0;
        boolean sawMainDocument = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    sawMainDocument = true;
                }
                total += drain(zip);
                if (total > maxUncompressedBytes) {
                    throw new ZipGuardException(
                            "Document expands beyond the maximum supported size");
                }
            }
        }
        if (!sawMainDocument) {
            throw new IOException("The DOCX package does not contain a main document");
        }
    }

    public static ScanResult scan(byte[] docxBytes, long maxUncompressedBytes) throws IOException {
        long total = 0;
        byte[] mainDocument = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    var collected = new java.io.ByteArrayOutputStream();
                    // Counted read, never an unbounded one. This is the ingest path, so it is the one
                    // that faces first-party untrusted input: readAllBytes() here would allocate the
                    // whole entry in order to decide it was too large, which is the exact bomb the
                    // ceiling exists to stop. The entry is only materialised after it has passed.
                    var buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        collected.write(buffer, 0, read);
                        total += read;
                        if (total > maxUncompressedBytes) {
                            throw new ZipGuardException(
                                    "Document expands beyond the maximum supported size");
                        }
                    }
                    mainDocument = collected.toByteArray();
                } else {
                    total += drain(zip);
                }
                if (total > maxUncompressedBytes) {
                    throw new ZipGuardException(
                            "Document expands beyond the maximum supported size");
                }
            }
        }
        if (mainDocument == null) {
            throw new IOException("The DOCX package does not contain a main document");
        }
        return new ScanResult(total, mainDocument);
    }

    /** The package exceeds the permitted uncompressed size. Distinct so callers can report it. */
    public static final class ZipGuardException extends IOException {
        private static final long serialVersionUID = 1L;

        ZipGuardException(String message) {
            super(message);
        }
    }

    private static long drain(ZipInputStream entryStream) throws IOException {
        long count = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = entryStream.read(buffer)) != -1) {
            count += read;
        }
        return count;
    }

    public static int countTopLevelBodyChildren(byte[] mainDocumentXml) throws IOException {
        var counter = new TopLevelBodyCounter();
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setNamespaceAware(true);
            var saxParser = factory.newSAXParser();
            saxParser.parse(new ByteArrayInputStream(mainDocumentXml), counter);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unreadable main document", e);
        }
        return counter.children();
    }

    private static final class TopLevelBodyCounter extends DefaultHandler {

        private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

        private int depth;
        private boolean inBody;
        private int children;

        int children() {
            return children;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            depth++;
            if (depth == 2 && W_NS.equals(uri) && "body".equals(localName)) {
                inBody = true;
            } else if (inBody && depth == 3 && !("sectPr".equals(localName) && W_NS.equals(uri))) {
                children++;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (depth == 2 && inBody) {
                inBody = false;
            }
            depth--;
        }
    }
}
