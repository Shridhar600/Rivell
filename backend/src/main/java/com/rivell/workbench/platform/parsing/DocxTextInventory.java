package com.rivell.workbench.platform.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.rivell.workbench.contracts.ParseCoverage.Location;

/**
 * Every paragraph of text in a DOCX, read from the raw package XML with StAX. Shares no code
 * with the structural parser on purpose: it is the independent side of the coverage check.
 *
 * <p>Text is the accept-all view: tracked insertions count, deleted text and field codes do
 * not. Word stores a text box twice (DrawingML choice and VML fallback); the fallback copy is
 * skipped. Paragraphs inside a text box are their own segments.
 *
 * <p>Callers must pass bytes ZipGuard has already bounded.
 */
final class DocxTextInventory {

    static final String MAIN_PART = "word/document.xml";

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String WML = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
    private static final Map<String, Location> STORY_TYPES = Map.of(
            WML + "header+xml", Location.HEADER,
            WML + "footer+xml", Location.FOOTER,
            WML + "footnotes+xml", Location.FOOTNOTE,
            WML + "endnotes+xml", Location.ENDNOTE,
            WML + "comments+xml", Location.COMMENT);

    /**
     * One paragraph (or cell) of source text. {@code aligned} is true for text the structure is
     * meant to hold, which coverage aligns word by word; false for parts it does not represent,
     * which coverage reports whole.
     */
    record Segment(String part, Location location, String text, boolean aligned) {
    }

    private DocxTextInventory() {
    }

    static List<Segment> read(byte[] docxBytes) throws IOException, XMLStreamException {
        var entries = entries(docxBytes);
        var contentTypes = contentTypes(entries.get("[Content_Types].xml"));
        var stories = new ArrayList<Map.Entry<String, Location>>();
        for (var name : entries.keySet()) {
            var location = STORY_TYPES.get(contentTypes.get(name));
            if (location != null) {
                stories.add(Map.entry(name, location));
            }
        }
        stories.sort(Comparator.<Map.Entry<String, Location>>comparingInt(e -> e.getValue().ordinal())
                .thenComparing(Map.Entry::getKey));

        var segments = new ArrayList<Segment>();
        var main = entries.get(MAIN_PART);
        if (main != null) {
            segments.addAll(readPart(MAIN_PART, main, null));
        }
        for (var story : stories) {
            segments.addAll(readPart(story.getKey(), entries.get(story.getKey()), story.getValue()));
        }
        return segments;
    }

    /** {@code fixed} is the location of every paragraph in a story part; null for the main part. */
    private static List<Segment> readPart(String part, byte[] xml, Location fixed)
            throws XMLStreamException {
        var segments = new ArrayList<Segment>();
        var open = new ArrayDeque<StringBuilder>();
        var openLocations = new ArrayDeque<Location>();
        var names = new ArrayDeque<String>();
        int deleted = 0;
        int textBoxes = 0;
        int tables = 0;
        int skipped = 0;
        boolean inText = false;
        var reader = reader(xml);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    var name = reader.getLocalName();
                    var ns = reader.getNamespaceURI();
                    var parent = names.peek();
                    names.push(name);
                    if (skipped > 0 || (MC.equals(ns) && "Fallback".equals(name))) {
                        skipped++;
                        continue;
                    }
                    if (!W.equals(ns)) {
                        continue;
                    }
                    switch (name) {
                        case "del", "moveFrom" -> deleted++;
                        case "txbxContent" -> textBoxes++;
                        case "tbl" -> tables++;
                        case "p" -> {
                            open.push(new StringBuilder());
                            openLocations.push(fixed != null ? fixed
                                    : textBoxes > 0 ? Location.TEXT_BOX
                                    : tables > 0 ? Location.TABLE : Location.BODY);
                        }
                        case "t" -> inText = deleted == 0;
                        case "tab", "br", "cr" -> {
                            if ("r".equals(parent) && deleted == 0 && !open.isEmpty()) {
                                open.peek().append(' ');
                            }
                        }
                        case "noBreakHyphen" -> {
                            if (deleted == 0 && !open.isEmpty()) {
                                open.peek().append('-');
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inText && skipped == 0 && !open.isEmpty()) {
                        open.peek().append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    names.pop();
                    var name = reader.getLocalName();
                    if (skipped > 0) {
                        skipped--;
                        continue;
                    }
                    if (!W.equals(reader.getNamespaceURI())) {
                        continue;
                    }
                    switch (name) {
                        case "del", "moveFrom" -> deleted--;
                        case "txbxContent" -> textBoxes--;
                        case "tbl" -> tables--;
                        case "t" -> inText = false;
                        case "p" -> {
                            var text = open.pop().toString();
                            var location = openLocations.pop();
                            if (!text.isBlank()) {
                                segments.add(new Segment(part, location, text, fixed == null));
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return segments;
    }

    /** Whether the main document part holds tracked insertions, deletions or moves. */
    static boolean hasTrackedChanges(byte[] mainDocumentXml) throws XMLStreamException {
        var reader = reader(mainDocumentXml);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && W.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "ins", "del", "moveFrom", "moveTo" -> {
                            return true;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return false;
    }

    private static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    /** Part name (no leading slash) to content type, from overrides and extension defaults. */
    private static Map<String, String> contentTypes(byte[] xml) throws XMLStreamException {
        var defaults = new HashMap<String, String>();
        var overrides = new HashMap<String, String>();
        if (xml == null) {
            return overrides;
        }
        var reader = reader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                var type = reader.getAttributeValue(null, "ContentType");
                if ("Override".equals(reader.getLocalName())) {
                    var partName = reader.getAttributeValue(null, "PartName");
                    if (partName != null) {
                        overrides.put(partName.startsWith("/") ? partName.substring(1) : partName, type);
                    }
                } else if ("Default".equals(reader.getLocalName())) {
                    defaults.put(reader.getAttributeValue(null, "Extension"), type);
                }
            }
        } finally {
            reader.close();
        }
        return new DefaultingMap(overrides, defaults);
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /** Override first, then the extension default. */
    private static final class DefaultingMap extends HashMap<String, String> {

        private final Map<String, String> defaults;

        DefaultingMap(Map<String, String> overrides, Map<String, String> defaults) {
            super(overrides);
            this.defaults = defaults;
        }

        @Override
        public String get(Object key) {
            var value = super.get(key);
            if (value != null || !(key instanceof String name)) {
                return value;
            }
            var dot = name.lastIndexOf('.');
            return dot < 0 ? null : defaults.get(name.substring(dot + 1));
        }
    }
}
