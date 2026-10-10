package ru;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The content of an XML file written by {@link TlkFile#storeToFile(String, FileFormat,
 * FxmlController)}, read with a streaming (StAX) parser so that even the 18 MB export of the
 * sample file is cheap to check. Parsing fails if the file is not well-formed.
 */
final class ExportedXml {

    /** One {@code <string>} element. */
    static final class Entry {
        String id;
        String position;
        String data;
        /** Names of the child elements, in document order. */
        final List<String> children = new ArrayList<>();
    }

    String rootName;
    /** {@code TLKToolVersion} attribute of the root, or null. */
    String toolVersion;
    /** All comments, in document order. */
    final List<String> comments = new ArrayList<>();
    final List<Entry> strings = new ArrayList<>();

    static ExportedXml parse(Path file) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        ExportedXml result = new ExportedXml();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            try {
                Entry current = null;
                String field = null;
                StringBuilder text = new StringBuilder();
                int depth = 0;
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String name = r.getLocalName();
                        if (depth == 1) {
                            result.rootName = name;
                            result.toolVersion = r.getAttributeValue(null, "TLKToolVersion");
                        } else if (depth == 2 && "string".equals(name)) {
                            current = new Entry();
                        } else if (depth == 3 && current != null) {
                            current.children.add(name);
                            field = name;
                            text.setLength(0);
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        if (field != null) {
                            text.append(r.getText());
                        }
                    } else if (event == XMLStreamConstants.COMMENT) {
                        result.comments.add(r.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == 3 && current != null && field != null) {
                            if ("id".equals(field)) {
                                current.id = text.toString();
                            } else if ("position".equals(field)) {
                                current.position = text.toString();
                            } else if ("data".equals(field)) {
                                current.data = text.toString();
                            }
                            field = null;
                        } else if (depth == 2 && current != null) {
                            result.strings.add(current);
                            current = null;
                        }
                        depth--;
                    }
                }
            } finally {
                r.close();
            }
        }
        return result;
    }
}
