package ru;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes {@link FileFormat#CSV}: comma-separated values as described by
 * <a href="https://www.rfc-editor.org/rfc/rfc4180">RFC 4180</a>, for spreadsheets such as Excel
 * or LibreOffice Calc.
 * <pre>
 * TLKToolVersion,1.0.4
 * id,position,hasText,text
 * 34931,0,true,Text of the string
 * -2147319074,1,false,
 * 218733,2,true,"First line
 * second line, with ""quotes"""
 * </pre>
 *
 * <h2>Writing</h2>
 * <ul>
 *   <li>The file is UTF-8 and starts with a byte order mark, so that Excel recognizes the
 *       encoding (and shows Cyrillic correctly). Records end with CRLF.</li>
 *   <li>The first record is {@code TLKToolVersion,<version>}, the second the header row
 *       {@code id,position,hasText,text}; then follows one record per entry in file order:
 *       the decimal string id, the position (index in the TLK file's string-ref table),
 *       {@code true} or {@code false} for whether the entry has text, and the text (empty for an
 *       entry without text).</li>
 *   <li>A field containing a comma, a double quote, a carriage return or a line feed is enclosed
 *       in double quotes, and every double quote in it is doubled; line breaks inside a text are
 *       written as they are (inside the quotes). Other fields are written as they are.</li>
 *   <li>A text with an unpaired surrogate cannot be written (UTF-8 cannot encode it; use
 *       {@link FileFormat#TXT} for such files).</li>
 * </ul>
 *
 * <h2>Reading</h2>
 * <ul>
 *   <li>A leading byte order mark is optional. Records may end with CRLF, LF or CR, the last one
 *       may have no line end, and fields follow RFC 4180 quoting; a quote inside an unquoted
 *       field, text after a closing quote or a quoted field that is never closed is an
 *       error.</li>
 *   <li>Records whose fields are all empty (such as blank lines) are skipped.</li>
 *   <li>The first record must be {@code TLKToolVersion,<version>} (further fields are ignored);
 *       the version is checked as for XML
 *       ({@link HuffmanCompression#checkToolVersion(String, String, String, Path)}).</li>
 *   <li>The second record is the header row. It must name the columns {@code id},
 *       {@code position}, {@code hasText} and {@code text} (in any order, ignoring case and
 *       surrounding spaces); other columns are ignored.</li>
 *   <li>In every following record, {@code id} and {@code position} must be 32-bit integers
 *       (surrounding spaces allowed) and {@code hasText} {@code true} or {@code false} in any
 *       case (Excel turns them into {@code TRUE}/{@code FALSE}); an entry without text must have
 *       an empty {@code text}. Entries are sorted by position on import, so rows may be
 *       reordered.</li>
 * </ul>
 * Errors name the line number (the line where the record starts).
 */
final class CsvFormat {

    /** Field separator. */
    static final char SEPARATOR = ',';
    /** Quote character. */
    static final char QUOTE = '"';
    /** First field of the version record. */
    static final String VERSION_KEY = XmlFormat.VERSION_ATTRIBUTE;
    /** Name of the id column. */
    static final String ID = "id";
    /** Name of the position column. */
    static final String POSITION = "position";
    /** Name of the column telling whether the entry has text. */
    static final String HAS_TEXT = "hasText";
    /** Name of the text column. */
    static final String TEXT = "text";
    /** The columns in the order they are written. */
    private static final List<String> COLUMNS = Arrays.asList(ID, POSITION, HAS_TEXT, TEXT);
    /** Record terminator written. */
    private static final String EOL = "\r\n";

    /** Not instantiable. */
    private CsvFormat() {
    }

    /**
     * Returns a writer for the format described in the class description.
     *
     * @param out destination
     * @return the writer
     */
    static FormatWriter writer(Writer out) {
        return new CsvWriter(out);
    }

    /** Writes the version record, the header row and one record per entry. */
    private static final class CsvWriter implements FormatWriter {
        /** Destination. */
        private final Writer out;

        /**
         * Creates the writer.
         *
         * @param out destination
         */
        CsvWriter(Writer out) {
            this.out = out;
        }

        /**
         * Writes the byte order mark, the version record and the header row.
         *
         * @param toolVersion the version
         * @throws IOException if writing fails
         */
        @Override
        public void begin(String toolVersion) throws IOException {
            out.write(InputFile.BOM);
            out.write(VERSION_KEY + SEPARATOR + quoteIfNeeded(toolVersion) + EOL);
            out.write(String.join(String.valueOf(SEPARATOR), COLUMNS) + EOL);
        }

        /**
         * Writes nothing: CSV has no comments.
         *
         * @param text ignored
         */
        @Override
        public void comment(String text) {
            // CSV has no comments
        }

        /**
         * Writes the record {@code id,position,hasText,text}.
         *
         * @param ref the entry
         * @throws IOException if writing fails or the text has an unpaired surrogate
         */
        @Override
        public void entry(TlkFile.TlkStringRef ref) throws IOException {
            boolean hasText = ref.data != null;
            if (hasText) {
                checkEncodable(ref);
            }
            out.write(ref.stringId + "," + ref.position + "," + hasText + ",");
            if (hasText) {
                out.write(quoteIfNeeded(ref.data));
            }
            out.write(EOL);
        }

        /** Writes nothing: the file ends with the last record. */
        @Override
        public void end() {
            // nothing after the last record
        }
    }

    /**
     * Checks that a text has no unpaired surrogate, which UTF-8 cannot encode.
     *
     * @param ref the entry
     * @throws IOException naming the entry and the character if it has one
     */
    private static void checkEncodable(TlkFile.TlkStringRef ref) throws IOException {
        String text = ref.data;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IOException("String " + ref.stringId + " (position " + ref.position
                        + ") cannot be written as CSV: it contains the unpaired surrogate U+"
                        + String.format("%04X", (int) c) + ", which UTF-8 cannot encode (use TXT instead)");
            }
        }
    }

    /**
     * Quotes a field if RFC 4180 requires it: if it contains a comma, a double quote, a carriage
     * return or a line feed, it is enclosed in double quotes with its double quotes doubled.
     *
     * @param field the field
     * @return the field as it is written
     */
    static String quoteIfNeeded(String field) {
        boolean needed = false;
        for (int i = 0; i < field.length() && !needed; i++) {
            char c = field.charAt(i);
            needed = c == SEPARATOR || c == QUOTE || c == '\r' || c == '\n';
        }
        if (!needed) {
            return field;
        }
        return QUOTE + field.replace(String.valueOf(QUOTE), "" + QUOTE + QUOTE) + QUOTE;
    }

    /**
     * Reads a CSV file.
     *
     * @param file the file
     * @return the version from the first record and the entries in file order
     * @throws IOException if the file cannot be read, is not UTF-8, lacks the version record or
     *                     a column, was written by a tool version that is too old or has a
     *                     malformed record or value; the message names the line
     */
    static InputFile read(Path file) throws IOException {
        Parser parser = new Parser(InputFile.readUtf8(file), file);

        List<String> record;
        try {
            record = parser.nextNonEmpty();
        } catch (IOException e) {
            // not even the first record is valid CSV: most likely not a CSV file at all
            IOException error = InputFile.error("Missing version record \"" + VERSION_KEY + ",<version>\" as the first"
                    + " record, found a malformed record", parser.recordLine, file);
            error.initCause(e);
            throw error;
        }
        if (record == null || !VERSION_KEY.equals(record.get(0).trim()) || record.size() < 2
                || record.get(1).trim().isEmpty()) {
            throw InputFile.error("Missing version record \"" + VERSION_KEY + ",<version>\" as the first record, found "
                    + (record == null ? "an empty file" : InputFile.quote(String.join(",", record))),
                    parser.recordLine, file);
        }
        String version = record.get(1).trim();
        HuffmanCompression.checkToolVersion(version, "CSV", "", file);

        record = parser.nextNonEmpty();
        if (record == null) {
            throw InputFile.error("Missing header row \"" + String.join(",", COLUMNS) + "\" after the version record",
                    parser.recordLine, file);
        }
        int[] columns = columnIndices(record, parser.recordLine, file);
        int width = 0;
        for (int column : columns) {
            width = Math.max(width, column + 1);
        }

        List<TlkEntry> entries = new ArrayList<>();
        while ((record = parser.nextNonEmpty()) != null) {
            entries.add(parseEntry(record, columns, width, parser.recordLine, file));
        }
        return new InputFile(version, entries);
    }

    /**
     * Finds the columns {@code id}, {@code position}, {@code hasText} and {@code text} in the
     * header row.
     *
     * @param header the header row
     * @param line   its line number, for error messages
     * @param file   the file, for error messages
     * @return the index of each column, in the order of {@link #COLUMNS}
     * @throws IOException if a column is missing or occurs twice
     */
    private static int[] columnIndices(List<String> header, int line, Path file) throws IOException {
        int[] indices = new int[COLUMNS.size()];
        Arrays.fill(indices, -1);
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim().toLowerCase(Locale.ROOT);
            for (int c = 0; c < COLUMNS.size(); c++) {
                if (COLUMNS.get(c).toLowerCase(Locale.ROOT).equals(name)) {
                    if (indices[c] >= 0) {
                        throw InputFile.error("Column \"" + COLUMNS.get(c) + "\" occurs twice in the header row",
                                line, file);
                    }
                    indices[c] = i;
                }
            }
        }
        for (int c = 0; c < COLUMNS.size(); c++) {
            if (indices[c] < 0) {
                throw InputFile.error("Missing column \"" + COLUMNS.get(c) + "\" in the header row "
                        + InputFile.quote(String.join(",", header)) + " (expected " + String.join(",", COLUMNS) + ")",
                        line, file);
            }
        }
        return indices;
    }

    /**
     * Turns a data record into an entry.
     *
     * @param record  the fields
     * @param columns index of each column, in the order of {@link #COLUMNS}
     * @param width   number of fields needed to reach every column
     * @param line    line number of the record, for error messages
     * @param file    the file, for error messages
     * @return the entry
     * @throws IOException if the record is too short or a value is invalid
     */
    private static TlkEntry parseEntry(List<String> record, int[] columns, int width, int line, Path file)
            throws IOException {
        if (record.size() < width) {
            throw InputFile.error("Expected " + width + " fields but found " + record.size(), line, file);
        }
        int id = InputFile.parseInt(record.get(columns[0]).trim(), ID, line, file);
        int position = InputFile.parseInt(record.get(columns[1]).trim(), POSITION, line, file);
        String hasText = record.get(columns[2]).trim();
        String text = record.get(columns[3]);
        if ("true".equalsIgnoreCase(hasText)) {
            return new TlkEntry(id, position, text);
        }
        if (!"false".equalsIgnoreCase(hasText)) {
            throw InputFile.error("Invalid " + HAS_TEXT + " " + InputFile.quote(hasText) + " (must be true or false)",
                    line, file);
        }
        if (!text.isEmpty()) {
            throw InputFile.error("String " + id + " has " + HAS_TEXT + "=false, so its text must be empty, not "
                    + InputFile.quote(text), line, file);
        }
        return new TlkEntry(id, position, null);
    }

    /** Splits the content of a CSV file into records of fields, following RFC 4180. */
    private static final class Parser {
        /** The whole file content, without byte order mark. */
        private final String content;
        /** The file, for error messages. */
        private final Path file;
        /** Index of the next character to read. */
        private int pos;
        /** 1-based number of the line holding {@link #pos}. */
        private int line = 1;
        /**
         * 1-based line number where the record last returned starts; after the end of the file,
         * the number of the line after the last line end.
         */
        int recordLine = 1;

        /**
         * Creates a parser.
         *
         * @param content the file content
         * @param file    the file, for error messages
         */
        Parser(String content, Path file) {
            this.content = content;
            this.file = file;
        }

        /**
         * Returns the next record that has a non-empty field.
         *
         * @return its fields, or {@code null} at the end of the file
         * @throws IOException if the record is malformed
         */
        List<String> nextNonEmpty() throws IOException {
            List<String> record;
            do {
                record = next();
            } while (record != null && isEmpty(record));
            return record;
        }

        /**
         * Tells whether all fields of a record are empty.
         *
         * @param record the fields
         * @return {@code true} if every field is the empty string
         */
        private static boolean isEmpty(List<String> record) {
            for (String field : record) {
                if (!field.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Reads the next record and the line end after it.
         *
         * @return its fields, or {@code null} at the end of the file
         * @throws IOException if a quoted field is not closed, is followed by something other
         *                     than a separator or a line end, or an unquoted field has a quote
         */
        List<String> next() throws IOException {
            recordLine = line;
            if (pos >= content.length()) {
                return null;
            }
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            while (true) {
                if (pos < content.length() && content.charAt(pos) == QUOTE) {
                    readQuoted(field);
                } else {
                    readUnquoted(field);
                }
                fields.add(field.toString());
                field.setLength(0);
                if (pos >= content.length()) {
                    return fields;
                }
                char c = content.charAt(pos++);
                if (c != SEPARATOR) {
                    // a line end: CR, LF or CRLF
                    if (c == '\r' && pos < content.length() && content.charAt(pos) == '\n') {
                        pos++;
                    }
                    line++;
                    return fields;
                }
            }
        }

        /**
         * Reads a quoted field, whose opening quote is at {@link #pos}, up to and including the
         * closing quote.
         *
         * @param field receives the unquoted content
         * @throws IOException if the field is not closed or the closing quote is followed by
         *                     something other than a separator or a line end
         */
        private void readQuoted(StringBuilder field) throws IOException {
            int startLine = line;
            pos++;
            while (true) {
                if (pos >= content.length()) {
                    throw InputFile.error("Quoted field starting at line " + startLine
                            + " is not closed (missing \" before the end of the file)", line, file);
                }
                char c = content.charAt(pos++);
                if (c == QUOTE) {
                    if (pos < content.length() && content.charAt(pos) == QUOTE) {
                        field.append(QUOTE);
                        pos++;
                        continue;
                    }
                    break;
                }
                if (c == '\n' || c == '\r' && (pos >= content.length() || content.charAt(pos) != '\n')) {
                    line++;
                }
                field.append(c);
            }
            if (pos < content.length()) {
                char c = content.charAt(pos);
                if (c != SEPARATOR && c != '\r' && c != '\n') {
                    throw InputFile.error("Unexpected " + InputFile.quote(String.valueOf(c))
                            + " after the closing quote of a field (a quote inside a quoted field must be doubled)",
                            line, file);
                }
            }
        }

        /**
         * Reads an unquoted field up to the next separator or line end.
         *
         * @param field receives the content
         * @throws IOException if the field contains a quote
         */
        private void readUnquoted(StringBuilder field) throws IOException {
            while (pos < content.length()) {
                char c = content.charAt(pos);
                if (c == SEPARATOR || c == '\r' || c == '\n') {
                    return;
                }
                if (c == QUOTE) {
                    throw InputFile.error("Unexpected \" in an unquoted field (enclose the field in quotes and"
                            + " double the quote)", line, file);
                }
                field.append(c);
                pos++;
            }
        }
    }
}
