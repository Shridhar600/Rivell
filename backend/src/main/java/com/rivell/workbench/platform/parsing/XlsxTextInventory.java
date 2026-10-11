package com.rivell.workbench.platform.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
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
 * Every text cell, cell comment and drawing text of an XLSX, read from the raw package XML with
 * StAX: the independent side of the knowledge coverage check (shares no code with the POI
 * extractor).
 *
 * <p>Worksheets are read in workbook order, cells in file order (row by row). Only string cells
 * count (shared, inline and cached formula strings); numbers, dates and booleans render through a
 * number format on the structure side, so their text cannot be compared word for word. Comments
 * and drawing text (shapes, text boxes) are parts the structure does not represent.
 *
 * <p>Callers must pass bytes whose expansion is already bounded.
 */
final class XlsxTextInventory {

    private static final String SML = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String DML = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String COMMENTS_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml";
    private static final String DRAWING_TYPE = "application/vnd.openxmlformats-officedocument.drawing+xml";

    private XlsxTextInventory() {
    }

    static List<DocxTextInventory.Segment> read(byte[] xlsxBytes) throws IOException, XMLStreamException {
        var entries = entries(xlsxBytes);
        var shared = sharedStrings(entries.get("xl/sharedStrings.xml"));
        var segments = new ArrayList<DocxTextInventory.Segment>();
        for (var sheet : orderedSheets(entries)) {
            var xml = entries.get(sheet);
            if (xml != null) {
                readCells(sheet, xml, shared, segments);
            }
        }
        var types = overrides(entries.get("[Content_Types].xml"));
        for (var entry : types.entrySet()) {
            var xml = entries.get(entry.getKey());
            if (xml == null) {
                continue;
            }
            if (COMMENTS_TYPE.equals(entry.getValue())) {
                readParagraphs(entry.getKey(), xml, SML, "comment", "t", Location.COMMENT, segments);
            } else if (DRAWING_TYPE.equals(entry.getValue())) {
                readParagraphs(entry.getKey(), xml, DML, "p", "t", Location.TEXT_BOX, segments);
            }
        }
        return segments;
    }

    private static void readCells(String part, byte[] xml, List<String> shared,
            List<DocxTextInventory.Segment> out) throws XMLStreamException {
        var reader = reader(xml);
        String type = null;
        StringBuilder value = null;
        StringBuilder inline = null;
        boolean inValue = false;
        boolean inInlineText = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && SML.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "c" -> {
                            type = reader.getAttributeValue(null, "t");
                            value = new StringBuilder();
                            inline = new StringBuilder();
                        }
                        case "v" -> inValue = true;
                        case "t" -> inInlineText = inline != null;
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inValue && value != null) {
                        value.append(reader.getText());
                    } else if (inInlineText && inline != null) {
                        inline.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && SML.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "v" -> inValue = false;
                        case "t" -> inInlineText = false;
                        case "c" -> {
                            var text = cellText(type, value, inline, shared);
                            if (text != null && !text.isBlank()) {
                                out.add(new DocxTextInventory.Segment(part, Location.TABLE, text, true));
                            }
                            type = null;
                            value = null;
                            inline = null;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    private static String cellText(String type, StringBuilder value, StringBuilder inline, List<String> shared) {
        if ("s".equals(type)) {
            try {
                var index = Integer.parseInt(value.toString().trim());
                return index >= 0 && index < shared.size() ? shared.get(index) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if ("inlineStr".equals(type)) {
            return inline.toString();
        }
        if ("str".equals(type)) {
            return value.toString();
        }
        return null;
    }

    /** Text of every {@code container} element, joining its {@code text} descendants. */
    private static void readParagraphs(String part, byte[] xml, String ns, String container, String text,
            Location location, List<DocxTextInventory.Segment> out) throws XMLStreamException {
        var reader = reader(xml);
        StringBuilder current = null;
        boolean inText = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && ns.equals(reader.getNamespaceURI())) {
                    if (container.equals(reader.getLocalName())) {
                        current = new StringBuilder();
                    } else if (text.equals(reader.getLocalName())) {
                        inText = current != null;
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && inText) {
                    current.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && ns.equals(reader.getNamespaceURI())) {
                    if (text.equals(reader.getLocalName())) {
                        inText = false;
                    } else if (container.equals(reader.getLocalName()) && current != null) {
                        if (!current.toString().isBlank()) {
                            out.add(new DocxTextInventory.Segment(part, location, current.toString(), false));
                        }
                        current = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    /** Shared string table; phonetic runs ({@code rPh}) are not part of the text. */
    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        var strings = new ArrayList<String>();
        if (xml == null) {
            return strings;
        }
        var reader = reader(xml);
        StringBuilder current = null;
        boolean inText = false;
        int phonetic = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && SML.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> phonetic++;
                        case "t" -> inText = current != null && phonetic == 0;
                        default -> {
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && inText) {
                    current.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && SML.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = false;
                        case "rPh" -> phonetic--;
                        case "si" -> {
                            strings.add(current.toString());
                            current = null;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return strings;
    }

    /** Worksheet part names in workbook order, via workbook.xml and its relationships. */
    private static List<String> orderedSheets(Map<String, byte[]> entries) throws XMLStreamException {
        var targets = new HashMap<String, String>();
        var rels = entries.get("xl/_rels/workbook.xml.rels");
        if (rels != null) {
            var reader = reader(rels);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "Relationship".equals(reader.getLocalName())) {
                        var target = reader.getAttributeValue(null, "Target");
                        if (target != null) {
                            targets.put(reader.getAttributeValue(null, "Id"), target.startsWith("/")
                                    ? target.substring(1) : "xl/" + target);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }
        var sheets = new ArrayList<String>();
        var workbook = entries.get("xl/workbook.xml");
        if (workbook == null) {
            return sheets;
        }
        var reader = reader(workbook);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && SML.equals(reader.getNamespaceURI())
                        && "sheet".equals(reader.getLocalName())) {
                    var target = targets.get(reader.getAttributeValue(REL, "id"));
                    if (target != null) {
                        sheets.add(target);
                    }
                }
            }
        } finally {
            reader.close();
        }
        return sheets;
    }

    private static Map<String, String> overrides(byte[] xml) throws XMLStreamException {
        var types = new LinkedHashMap<String, String>();
        if (xml == null) {
            return types;
        }
        var reader = reader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "Override".equals(reader.getLocalName())) {
                    var name = reader.getAttributeValue(null, "PartName");
                    if (name != null) {
                        types.put(name.startsWith("/") ? name.substring(1) : name,
                                reader.getAttributeValue(null, "ContentType"));
                    }
                }
            }
        } finally {
            reader.close();
        }
        return types;
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

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }
}
