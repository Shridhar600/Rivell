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
final class ZipGuard {

    record ScanResult(long totalUncompressedBytes, byte[] mainDocumentXml) {
    }

    private ZipGuard() {
    }

    static ScanResult scan(byte[] docxBytes, long maxUncompressedBytes) throws IOException {
        long total = 0;
        byte[] mainDocument = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    mainDocument = zip.readAllBytes();
                    total += mainDocument.length;
                } else {
                    total += drain(zip);
                }
                if (total > maxUncompressedBytes) {
                    throw new IOException("Document expands beyond the maximum supported size");
                }
            }
        }
        if (mainDocument == null) {
            throw new IOException("The DOCX package does not contain a main document");
        }
        return new ScanResult(total, mainDocument);
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
