package com.rivell.workbench.platform.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Builds damaged and augmented DOCX packages out of the existing small test fixtures. */
public final class DocxTestPackages {

    public static final String RELS_ENTRY = "word/_rels/document.xml.rels";
    public static final String STYLES_RELATIONSHIP = "<Relationship Id=\"rIdStyles\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" "
            + "Target=\"styles.xml\"/>";
    public static final String FOOTNOTES_RELATIONSHIP = "<Relationship Id=\"rIdFootnotes\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes\" "
            + "Target=\"footnotes\"/>";
    public static final String COMMENTS_RELATIONSHIP = "<Relationship Id=\"rIdComments\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" "
            + "Target=\"comments\"/>";

    private static final String RELATIONSHIPS_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    private DocxTestPackages() {
    }

    public static byte[] fixture(String name) {
        try (var stream = DocxTestPackages.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }

    public static String relsDocument(String... relationships) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + RELATIONSHIPS_NS + "\">\n"
                + String.join("\n", relationships)
                + "\n</Relationships>\n";
    }

    /** The package with one entry added or replaced; entry order and all other bytes are kept. */
    public static byte[] withEntry(byte[] sourceBytes, String entryName, byte[] replacement) {
        var entries = entries(sourceBytes);
        entries.put(entryName, replacement);
        return zip(entries);
    }

    public static byte[] withEntry(byte[] sourceBytes, String entryName, String replacement) {
        return withEntry(sourceBytes, entryName, replacement.getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, byte[]> entries(byte[] sourceBytes) {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(sourceBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read package entries", e);
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write package entries", e);
        }
        return output.toByteArray();
    }
}
