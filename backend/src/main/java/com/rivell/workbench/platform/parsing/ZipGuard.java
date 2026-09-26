package com.rivell.workbench.platform.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Bounds decompression of untrusted DOCX input before docx4j loads it: docx4j reads zip
 * entries into memory without an uncompressed-size cap. Also counts true top-level body
 * children so loader-side dropping of content becomes visible.
 */
/**
 * Uncompressed-size bound for a DOCX package, applied before docx4j materialises it.
 *
 * <p>Public because the redline renderer and verifier live in a sibling package and load packages
 * too; they must not be the one path in the system where a decompression bomb reaches the heap.
 */
public final class ZipGuard {

    public record ScanResult(long totalUncompressedBytes, byte[] mainDocumentXml) {
    }

    private ZipGuard() {
    }

    /**
     * Verifies the package without materialising anything.
     *
     * <p>M10 review finding: the main-document branch used {@code readAllBytes()} and only compared
     * the total afterwards, so the single largest entry in a real DOCX was fully allocated in order to
     * decide it was too large. Every other entry was already drained through a counting read; this
     * exists because the redline path needs the bound and has no use for the main part.
     */
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
                    mainDocument = zip.readAllBytes();
                    total += mainDocument.length;
                    if (total > maxUncompressedBytes) {
                        throw new ZipGuardException(
                                "Document expands beyond the maximum supported size");
                    }
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

    static int countTopLevelBodyChildren(byte[] mainDocumentXml) throws IOException {
        var counter = new TopLevelBodyCounter();
        try {
            var factory = SAXParserFactory.newInstance();
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
