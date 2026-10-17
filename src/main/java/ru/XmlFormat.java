package ru;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link FileFormat#XML}, the format of the C# original:
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <tlkFile TLKToolVersion="1.0.4">
 *     <!--Male entries section begin (ends at position 103969)-->
 *     <string>
 *         <id>34931</id>
 *         <position>0</position>
 *         <data>text of the string</data>
 *     </string>
 *     ...
 * </tlkFile>
 * }</pre>
 * One {@code <string>} per string ref, in file order; {@code <position>} is the ref's index in
 * the file, which {@link HuffmanCompression} uses to restore the order. As in the C# original, an
 * entry without text has the {@code <data>} {@code -1}, and an entry is taken to have text exactly
 * if its id is not negative (true for every entry of the game's files). An entry that breaks this
 * rule gets the attribute {@code hasText="false"} or {@code hasText="true"} on its {@code <data>}
 * element, so that it survives the round trip; older readers ignore the attribute. Carriage
 * returns are written as {@code &#13;} so that XML parsers do not turn them into line feeds. The
 * file is UTF-8, indented with 4 spaces, with LF line ends.
 */
final class XmlFormat {

    /** Name of the XML root element. */
    static final String ROOT = "tlkFile";
    /** Name of the root element's attribute holding the version of the tool that wrote the file. */
    static final String VERSION_ATTRIBUTE = "TLKToolVersion";
    /** Name of the XML element of one string ref. */
    static final String STRING = "string";
    /** Name of the XML element holding a string ref's id. */
    static final String ID = "id";
    /** Name of the XML element holding a string ref's position in the file. */
    static final String POSITION = "position";
    /** Name of the XML element holding a string ref's text. */
    static final String DATA = "data";
    /**
     * Optional attribute of {@code <data>} that says whether the entry has text, written only where
     * the id's sign would suggest otherwise (see the class description).
     */
    static final String HAS_TEXT = "hasText";
    /** {@code <data>} content of an entry without text. */
    static final String NO_TEXT = "-1";
    /** Indentation of one XML nesting level. */
    private static final String INDENT = "    ";

    /** Not instantiable. */
    private XmlFormat() {
    }

    /**
     * Returns a writer for the XML document described in the class description.
     *
     * @param out destination
     * @return the writer
     */
    static FormatWriter writer(Writer out) {
        return new XmlWriter(out);
    }

    /** Writes the {@code <tlkFile>} document. */
    private static final class XmlWriter implements FormatWriter {
        /** Destination. */
        private final Writer out;
        /** One {@code <string>} element, built here and written with a single call. */
        private final StringBuilder element = new StringBuilder();

        /**
         * Creates the writer.
         *
         * @param out destination
         */
        XmlWriter(Writer out) {
            this.out = out;
        }

        /**
         * Writes the XML declaration and the root start tag with the {@code TLKToolVersion}.
         *
         * @param toolVersion the version
         * @throws IOException if writing fails
         */
        @Override
        public void begin(String toolVersion) throws IOException {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write("<" + ROOT + " " + VERSION_ATTRIBUTE + "=\"" + toolVersion + "\">\n");
        }

        /**
         * Writes an XML comment on its own line, indented by one level.
         *
         * @param text comment text; must not contain {@code "--"}
         * @throws IOException if writing fails
         */
        @Override
        public void comment(String text) throws IOException {
            out.write(INDENT + "<!--" + text + "-->\n");
        }

        /**
         * Writes one {@code <string>} element.
         *
         * @param ref the entry
         * @throws IOException if writing fails or the text cannot be represented in XML
         */
        @Override
        public void entry(TlkFile.TlkStringRef ref) throws IOException {
            boolean hasText = ref.data != null;
            StringBuilder e = element;
            e.setLength(0);
            e.append(INDENT + "<" + STRING + ">\n");
            e.append(INDENT + INDENT + "<" + ID + ">").append(ref.stringId).append("</" + ID + ">\n");
            e.append(INDENT + INDENT + "<" + POSITION + ">").append(ref.position).append("</" + POSITION + ">\n");
            e.append(INDENT + INDENT + "<" + DATA);
            if (hasText != (ref.stringId >= 0)) {
                e.append(" " + HAS_TEXT + "=\"").append(hasText).append('"');
            }
            e.append('>');
            if (hasText) {
                appendEscaped(ref, e);
            } else {
                e.append(NO_TEXT);
            }
            e.append("</" + DATA + ">\n");
            e.append(INDENT + "</" + STRING + ">\n");
            out.append(e);
        }

        /**
         * Writes the root end tag.
         *
         * @throws IOException if writing fails
         */
        @Override
        public void end() throws IOException {
            out.write("</" + ROOT + ">\n");
        }

        /**
         * Appends the text of an entry as XML character data: {@code &}, {@code <} and {@code >}
         * become entity references, a carriage return becomes {@code &#13;}, everything else is
         * written as is.
         *
         * @param ref the entry whose {@link TlkFile.TlkStringRef#data} to write
         * @param out where to append it
         * @throws IOException if the text contains a character XML 1.0 does not allow (a control
         *                     character other than tab, line feed and carriage return, an unpaired
         *                     surrogate, {@code U+FFFE} or {@code U+FFFF})
         */
        private static void appendEscaped(TlkFile.TlkStringRef ref, StringBuilder out) throws IOException {
            String text = ref.data;
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                String replacement;
                if (c == '&') {
                    replacement = "&amp;";
                } else if (c == '<') {
                    replacement = "&lt;";
                } else if (c == '>') {
                    replacement = "&gt;";
                } else if (c == '\r') {
                    replacement = "&#13;";
                } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                } else if (isXmlChar(c)) {
                    continue;
                } else {
                    throw new IOException("String " + ref.stringId + " (position " + ref.position
                            + ") cannot be written as XML: it contains the character U+"
                            + String.format("%04X", (int) c) + ", which XML 1.0 does not allow");
                }
                out.append(text, start, i).append(replacement);
                start = i + 1;
            }
            out.append(text, start, text.length());
        }
    }

    /**
     * Tells whether a UTF-16 unit may appear on its own in XML 1.0 character data.
     *
     * @param c the character
     * @return {@code true} for tab, line feed, carriage return and {@code U+0020} to
     *         {@code U+FFFD} except surrogates
     */
    private static boolean isXmlChar(char c) {
        return c >= 0x20 ? !Character.isSurrogate(c) && c != 0xFFFE && c != 0xFFFF
                : c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * Parses an XML file.
     *
     * <p>The root element must be {@code <tlkFile>}; its {@code TLKToolVersion} attribute (missing:
     * {@link HuffmanCompression#DEFAULT_TOOL_VERSION}) is checked with
     * {@link HuffmanCompression#checkToolVersion(String, String, String, Path)} before any entry is
     * read. Every {@code <string>} element below it becomes an entry (see
     * {@link #readEntry(XMLStreamReader, Path)}). DTDs and external entities are not processed.
     *
     * @param file the XML file
     * @return the version and the entries in document order
     * @throws IOException if the file cannot be read, is not well-formed XML, is not a
     *                     {@code <tlkFile>} document, was written by a tool version that is too
     *                     old or has an invalid entry
     */
    static InputFile read(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                String version = readRoot(reader, file);
                List<TlkEntry> entries = new ArrayList<>();
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && STRING.equals(reader.getLocalName())) {
                        entries.add(readEntry(reader, file));
                    }
                }
                return new InputFile(version, entries);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Malformed XML in " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Moves to the root element, checks its name and the tool version.
     *
     * @param reader reader at the start of the document; left at the root's start tag
     * @param file   the file, for error messages
     * @return the tool version
     * @throws IOException        if the root is not {@code <tlkFile>} or the version is invalid
     *                            or too old
     * @throws XMLStreamException if the XML is malformed
     */
    private static String readRoot(XMLStreamReader reader, Path file) throws IOException, XMLStreamException {
        while (reader.next() != XMLStreamConstants.START_ELEMENT) {
            // skip the prolog: declaration, comments, processing instructions, whitespace
        }
        if (!ROOT.equals(reader.getLocalName())) {
            throw new IOException("Not a TLK XML file (root element <" + reader.getLocalName() + "> instead of <"
                    + ROOT + ">): " + file);
        }
        String attribute = reader.getAttributeValue(null, VERSION_ATTRIBUTE);
        String version = attribute == null ? HuffmanCompression.DEFAULT_TOOL_VERSION : attribute;
        HuffmanCompression.checkToolVersion(version, "XML",
                attribute == null ? " (it has no " + VERSION_ATTRIBUTE + " attribute)" : "", file);
        return version;
    }

    /**
     * Reads one {@code <string>} element.
     *
     * <p>Reads the {@code <id>}, {@code <position>} and {@code <data>} children (missing ones
     * default to 0, 0 and empty text; other children are ignored). Whether the entry has text is
     * taken from the {@code hasText} attribute of {@code <data>} if present, otherwise it has text
     * exactly if the id is not negative. An entry without text must have a negative number
     * (normally {@code -1}) as its {@code <data>}.
     *
     * @param reader reader at the {@code <string>} start tag; left at its end tag
     * @param file   the file, for error messages
     * @return the entry, with the text exactly as in the file
     * @throws IOException        if a value is invalid
     * @throws XMLStreamException if the XML is malformed
     */
    private static TlkEntry readEntry(XMLStreamReader reader, Path file) throws IOException, XMLStreamException {
        int line = reader.getLocation().getLineNumber();
        int id = 0;
        int position = 0;
        String data = "";
        String hasTextAttribute = null;
        while (reader.next() != XMLStreamConstants.END_ELEMENT) {
            if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String name = reader.getLocalName();
            if (ID.equals(name)) {
                id = parseInt(readText(reader), name, line, file);
            } else if (POSITION.equals(name)) {
                position = parseInt(readText(reader), name, line, file);
            } else if (DATA.equals(name)) {
                hasTextAttribute = reader.getAttributeValue(null, HAS_TEXT);
                data = readText(reader);
            } else {
                readText(reader);
            }
        }

        boolean hasText = hasTextAttribute == null ? id >= 0 : parseBoolean(hasTextAttribute, line, file);
        if (!hasText) {
            if (parseInt(data, DATA, line, file) >= 0) {
                throw new IOException("The <string> at line " + line + " (id " + id + ") has no text, so its <"
                        + DATA + "> must be " + NO_TEXT + ", not \"" + data + "\": " + file);
            }
            return new TlkEntry(id, position, null);
        }
        return new TlkEntry(id, position, data);
    }

    /**
     * Reads the text content of the current element, including that of nested elements, and
     * moves to its end tag.
     *
     * @param reader reader at a start tag
     * @return the concatenated character data
     * @throws XMLStreamException if the XML is malformed
     */
    private static String readText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        text.append(reader.getText());
                default -> {
                    // comments and processing instructions are not part of the text
                }
            }
        }
        return text.toString();
    }

    /**
     * Parses the integer content of an element, ignoring surrounding whitespace.
     *
     * @param text    the content
     * @param element element name, for the error message
     * @param line    line of the enclosing {@code <string>}, for the error message
     * @param file    the file, for the error message
     * @return the value
     * @throws IOException if {@code text} is not an integer
     */
    private static int parseInt(String text, String element, int line, Path file) throws IOException {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid <" + element + "> \"" + text + "\" in the <string> at line " + line
                    + " (not an integer): " + file, e);
        }
    }

    /**
     * Parses a {@code hasText} attribute.
     *
     * @param text the attribute value
     * @param line line of the enclosing {@code <string>}, for the error message
     * @param file the file, for the error message
     * @return the value
     * @throws IOException unless {@code text} is {@code true} or {@code false}
     */
    private static boolean parseBoolean(String text, int line, Path file) throws IOException {
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new IOException("Invalid " + HAS_TEXT + "=\"" + text + "\" in the <string> at line " + line
                + " (must be true or false): " + file);
    }
}
