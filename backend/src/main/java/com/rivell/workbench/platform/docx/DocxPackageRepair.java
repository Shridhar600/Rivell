package com.rivell.workbench.platform.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Word opens a package whose relationships point at missing parts after offering to repair it;
 * docx4j refuses the whole package. This removes every internal relationship whose resolved
 * target is not a part of the package, so the parser and the redline readers can still load the
 * file. The stored original is never changed.
 *
 * <p>Callers must pass bytes whose expansion has already been bounded by the zip-bomb guard.
 * Every entry that is not a rewritten {@code .rels} is copied byte for byte.
 */
public final class DocxPackageRepair {

    private static final String RELATIONSHIPS_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String RELS_SUFFIX = ".rels";

    /** The bytes to load, and whether any relationship was removed. */
    public record Result(byte[] bytes, boolean repaired) {
    }

    private DocxPackageRepair() {
    }

    public static Result repair(byte[] docxBytes) throws IOException {
        var scan = scan(docxBytes);
        var rewrittenRels = new LinkedHashMap<String, byte[]>();
        for (var rels : scan.relationshipParts().entrySet()) {
            var rewritten = withoutDanglingRelationships(
                    rels.getKey(), rels.getValue(), scan.knownParts());
            if (rewritten != null) {
                rewrittenRels.put(rels.getKey(), rewritten);
            }
        }
        if (rewrittenRels.isEmpty()) {
            return new Result(docxBytes, false);
        }
        // Only a damaged package pays for the copy: every part is read, the rewritten .rels swapped in.
        var result = entries(docxBytes);
        result.putAll(rewrittenRels);
        return new Result(zipBytes(result), true);
    }

    /** What the check needs from a package: every part name, and the bytes of each {@code .rels}. */
    private record Scan(Set<String> knownParts, Map<String, byte[]> relationshipParts) {
    }

    /**
     * One pass over the package. The data of every other entry is skipped, never kept, so an
     * intact package costs no more than its small relationship parts.
     */
    private static Scan scan(byte[] docxBytes) throws IOException {
        var knownParts = new HashSet<String>();
        var relationshipParts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                knownParts.add(entry.getName().toLowerCase(Locale.ROOT));
                if (!entry.isDirectory()
                        && entry.getName().toLowerCase(Locale.ROOT).endsWith(RELS_SUFFIX)) {
                    relationshipParts.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return new Scan(knownParts, relationshipParts);
    }

    /**
     * A {@code .rels} with at least one relationship whose target part is absent, rewritten
     * without it; null when nothing was removed or the part cannot be read or written (an
     * unusable part is left unchanged, never dropped).
     */
    private static byte[] withoutDanglingRelationships(
            String relsName, byte[] xml, Set<String> knownParts) {
        Document document;
        try {
            document = secureFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            return null;
        }
        var sourcePart = sourcePart(relsName);
        var relationships = document.getElementsByTagNameNS(RELATIONSHIPS_NS, "Relationship");
        var dangling = new ArrayList<Element>();
        for (int i = 0; i < relationships.getLength(); i++) {
            var relationship = (Element) relationships.item(i);
            if ("External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))) {
                continue;
            }
            var target = resolve(sourcePart, relationship.getAttribute("Target"));
            if (!knownParts.contains(target.toLowerCase(Locale.ROOT))) {
                dangling.add(relationship);
            }
        }
        if (dangling.isEmpty()) {
            return null;
        }
        for (var relationship : dangling) {
            relationship.getParentNode().removeChild(relationship);
        }
        try {
            var output = new ByteArrayOutputStream();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** DOCTYPEs and external entities stay out: an entity in a part name is never expanded. */
    private static DocumentBuilderFactory secureFactory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /**
     * The part a {@code .rels} belongs to, per OPC: {@code word/_rels/document.xml.rels} is the
     * relationship set of {@code word/document.xml}; package-level {@code _rels/.rels} resolves
     * from the package root.
     */
    private static String sourcePart(String relsName) {
        var marker = relsName.toLowerCase(Locale.ROOT).lastIndexOf("/_rels/");
        if (marker < 0) {
            return "";
        }
        var folder = relsName.substring(0, marker + 1);
        var sourceName = relsName.substring(marker + "/_rels/".length());
        return sourceName.toLowerCase(Locale.ROOT).endsWith(RELS_SUFFIX)
                ? folder + sourceName.substring(0, sourceName.length() - RELS_SUFFIX.length())
                : folder;
    }

    /** The OPC way: drop the fragment, percent-decode, resolve relative to the source part. */
    private static String resolve(String sourcePart, String target) {
        var withoutFragment = target;
        var fragment = withoutFragment.indexOf('#');
        if (fragment >= 0) {
            withoutFragment = withoutFragment.substring(0, fragment);
        }
        var path = percentDecode(withoutFragment);
        if (path.isEmpty()) {
            return normalize(sourcePart);
        }
        if (path.startsWith("/")) {
            return normalize(path.substring(1));
        }
        var lastSlash = sourcePart.lastIndexOf('/');
        var folder = lastSlash < 0 ? "" : sourcePart.substring(0, lastSlash + 1);
        return normalize(folder + path);
    }

    private static String normalize(String path) {
        var segments = new ArrayDeque<String>();
        for (var segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return String.join("/", segments);
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        var decoded = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            if (character == '%' && i + 2 < value.length()
                    && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                decoded.write((hex(value.charAt(i + 1)) << 4) | hex(value.charAt(i + 2)));
                i += 2;
            } else {
                var bytes = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
                decoded.write(bytes, 0, bytes.length);
            }
        }
        return new String(decoded.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(char character) {
        return Character.digit(character, 16) >= 0;
    }

    private static int hex(char character) {
        return Character.digit(character, 16);
    }

    /** Entries in package order; a directory entry keeps its name and carries no bytes. */
    private static Map<String, byte[]> entries(byte[] docxBytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), entry.isDirectory() ? new byte[0] : zip.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
