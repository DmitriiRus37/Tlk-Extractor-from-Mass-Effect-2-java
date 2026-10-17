package ru;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link FileFormat#TXT}: a plain UTF-8 text file with one line per entry,
 * meant to be read and edited in any text editor.
 * <pre>
 * # TLKToolVersion=1.0.4
 * # Male entries section begin (ends at position 103969)
 * 34931: Text of the string
 * -2147319074: \N
 * 218733: First line\nsecond line with a \\ backslash
 * </pre>
 *
 * <h2>Writing</h2>
 * <ul>
 *   <li>The first line is the header {@code # TLKToolVersion=<version>}.</li>
 *   <li>Every entry is written as {@code <id>: <text>}: the decimal string id, a colon, one
 *       space and the escaped text, in file order. An entry without text has the text
 *       {@code \N}; an empty text leaves nothing after the space.</li>
 *   <li>Escapes: backslash is written as {@code \\}, line feed as {@code \n}, carriage return
 *       as {@code \r}, tab as {@code \t}; other control characters ({@code U+0000}-{@code U+001F},
 *       {@code U+007F}-{@code U+009F}), {@code U+2028}, {@code U+2029} and unpaired surrogates as
 *       {@code \}{@code uXXXX} (4 upper-case hex digits). Everything else, Cyrillic included, is
 *       written as is, so every text fits on one line and every TLK file can be written.</li>
 *   <li>Lines starting with {@code #} are comments; the section comments of the XML format are
 *       written as such. Lines end with CRLF, as in the old plain-text export.</li>
 * </ul>
 *
 * <h2>Reading</h2>
 * <ul>
 *   <li>A leading byte order mark is skipped. Lines may end with CRLF, LF or CR.</li>
 *   <li>The first line must be the header; the version is checked as for XML
 *       ({@link HuffmanCompression#checkToolVersion(String, String, String, Path)}). The old
 *       plain-text export, which had no header and could not mark entries without text or
 *       multi-line texts, is therefore rejected.</li>
 *   <li>Comment lines and blank lines (empty or only spaces and tabs) are skipped.</li>
 *   <li>Every other line must be {@code <id>:} followed by an optional single space and the
 *       text; spaces after that belong to the text. {@code \N} as the whole text means "no
 *       text"; otherwise the escapes above are decoded ({@code \}{@code u} takes any 4 hex digits)
 *       and any other backslash sequence is an error.</li>
 *   <li>There are no positions: the order of the entry lines is the position order, so the
 *       {@code n}-th entry line gets position {@code n - 1}.</li>
 * </ul>
 * Errors name the line number.
 */
final class TxtFormat {

    /** Start of the header line, followed by {@code =} and the version. */
    static final String HEADER = "# " + XmlFormat.VERSION_ATTRIBUTE;
    /** Character starting a comment line. */
    static final char COMMENT = '#';
    /** Text of an entry without text. */
    static final String NO_TEXT = "\\N";
    /** Line terminator written. */
    private static final String EOL = "\r\n";
    /** Hex digits for {@code \}{@code uXXXX} escapes. */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Not instantiable. */
    private TxtFormat() {
    }

    /**
     * Returns a writer for the format described in the class description.
     *
     * @param out destination
     * @return the writer
     */
    static FormatWriter writer(Writer out) {
        return new TxtWriter(out);
    }

    /** Writes the header, comment and entry lines. */
    private static final class TxtWriter implements FormatWriter {
        /** Destination. */
        private final Writer out;

        /**
         * Creates the writer.
         *
         * @param out destination
         */
        TxtWriter(Writer out) {
            this.out = out;
        }

        /**
         * Writes the header line {@code # TLKToolVersion=<version>}.
         *
         * @param toolVersion the version
         * @throws IOException if writing fails
         */
        @Override
        public void begin(String toolVersion) throws IOException {
            out.write(HEADER + "=" + toolVersion + EOL);
        }

        /**
         * Writes {@code "# " + text} as a line.
         *
         * @param text the comment
         * @throws IOException if writing fails
         */
        @Override
        public void comment(String text) throws IOException {
            out.write(COMMENT + " " + text + EOL);
        }

        /**
         * Writes the line {@code <id>: <escaped text>}, or {@code <id>: \N} for an entry without
         * text.
         *
         * @param ref the entry
         * @throws IOException if writing fails
         */
        @Override
        public void entry(TlkFile.TlkStringRef ref) throws IOException {
            out.write(Integer.toString(ref.stringId));
            out.write(": ");
            out.write(ref.data == null ? NO_TEXT : escape(ref.data));
            out.write(EOL);
        }

        /** Writes nothing: the file ends with the last line. */
        @Override
        public void end() {
            // nothing after the last entry
        }
    }

    /**
     * Escapes a text for one line of the file (see the class description).
     *
     * @param text the text
     * @return the escaped text, without line breaks
     */
    static String escape(String text) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement;
            if (c == '\\') {
                replacement = "\\\\";
            } else if (c == '\n') {
                replacement = "\\n";
            } else if (c == '\r') {
                replacement = "\\r";
            } else if (c == '\t') {
                replacement = "\\t";
            } else if (needsUnicodeEscape(text, i)) {
                replacement = "\\u" + HEX[c >>> 12] + HEX[(c >>> 8) & 0xF] + HEX[(c >>> 4) & 0xF] + HEX[c & 0xF];
            } else {
                if (sb != null) {
                    sb.append(c);
                }
                if (Character.isHighSurrogate(c)) {
                    // a valid pair (needsUnicodeEscape returned false): copy the low surrogate too
                    if (sb != null) {
                        sb.append(text.charAt(i + 1));
                    }
                    i++;
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            sb.append(replacement);
        }
        return sb == null ? text : sb.toString();
    }

    /**
     * Tells whether the character at an index must be written as {@code \}{@code uXXXX}: a
     * control character other than tab, line feed and carriage return, a line or paragraph
     * separator, or a surrogate that is not part of a valid pair.
     *
     * @param text  the text
     * @param index index of the character
     * @return {@code true} if it must be escaped
     */
    private static boolean needsUnicodeEscape(String text, int index) {
        char c = text.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1));
        }
        return Character.isISOControl(c) || Character.isLowSurrogate(c) || c == ' ' || c == ' ';
    }

    /**
     * Reads a TXT file.
     *
     * @param file the file
     * @return the version from the header and the entries in line order, with positions 0, 1, ...
     * @throws IOException if the file cannot be read, is not UTF-8, has no valid header, was
     *                     written by a tool version that is too old or has a malformed line; the
     *                     message names the line
     */
    static InputFile read(Path file) throws IOException {
        String content = InputFile.readUtf8(file);
        List<TlkEntry> entries = new ArrayList<>();
        String version = null;
        int lineNumber = 0;
        int start = 0;
        // the next line feed at or after start (or the end); found again only once a line passes it,
        // so that a file with CR line ends is not scanned to its end for every line
        int lineFeed = -1;
        while (start < content.length() || lineNumber == 0) {
            if (lineFeed < start) {
                lineFeed = content.indexOf('\n', start);
                if (lineFeed < 0) {
                    lineFeed = content.length();
                }
            }
            int carriageReturn = content.indexOf('\r', start, lineFeed);
            int end = carriageReturn >= 0 ? carriageReturn : lineFeed;
            int lineStart = start;
            int lineEnd = end;
            lineNumber++;
            if (end < content.length() && content.charAt(end) == '\r' && end + 1 < content.length()
                    && content.charAt(end + 1) == '\n') {
                end++;
            }
            start = end + 1;

            if (version == null) {
                version = parseHeader(content.substring(lineStart, lineEnd), file);
            } else if (!isBlank(content, lineStart, lineEnd) && content.charAt(lineStart) != COMMENT) {
                entries.add(parseEntry(content, lineStart, lineEnd, entries.size(), lineNumber, file));
            }
        }
        return new InputFile(version, entries);
    }

    /**
     * Parses and checks the header line.
     *
     * @param line the first line
     * @param file the file, for error messages
     * @return the version
     * @throws IOException if the line is not a header or the version is invalid or too old
     */
    private static String parseHeader(String line, Path file) throws IOException {
        String version = null;
        if (line.startsWith(String.valueOf(COMMENT))) {
            String rest = line.substring(1).trim();
            if (rest.startsWith(XmlFormat.VERSION_ATTRIBUTE)) {
                rest = rest.substring(XmlFormat.VERSION_ATTRIBUTE.length()).trim();
                if (rest.startsWith("=")) {
                    version = rest.substring(1).trim();
                }
            }
        }
        if (version == null || version.isEmpty()) {
            throw InputFile.error("Missing header \"" + HEADER + "=<version>\" in the first line, found "
                    + InputFile.quote(line) + " (plain-text exports of older versions cannot be imported)", 1, file);
        }
        HuffmanCompression.checkToolVersion(version, "TXT", "", file);
        return version;
    }

    /**
     * Tells whether a line holds nothing but spaces and tabs.
     *
     * @param content the file content
     * @param start   index of the first character of the line
     * @param end     index after its last character
     * @return {@code true} if it is blank
     */
    private static boolean isBlank(String content, int start, int end) {
        for (int i = start; i < end; i++) {
            if (content.charAt(i) != ' ' && content.charAt(i) != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an entry line in place, without copying the line: the common case of an id of 1 to 9
     * digits is handled here, anything else by {@link #parseEntry(String, int, int, Path)}, which
     * also produces every error message.
     *
     * @param content    the file content
     * @param start      index of the first character of the line
     * @param end        index after its last character
     * @param position   position to give the entry
     * @param lineNumber 1-based line number, for error messages
     * @param file       the file, for error messages
     * @return the entry
     * @throws IOException if the line is not {@code <id>: <text>} or the text has an invalid
     *                     escape sequence
     */
    private static TlkEntry parseEntry(String content, int start, int end, int position, int lineNumber, Path file)
            throws IOException {
        int colon = content.indexOf(':', start, end);
        int digits = colon > start && content.charAt(start) == '-' ? start + 1 : start;
        int id = colon - digits >= 1 && colon - digits <= 9 ? 0 : -1;
        for (int i = digits; i < colon && id >= 0; i++) {
            char c = content.charAt(i);
            id = c >= '0' && c <= '9' ? 10 * id + (c - '0') : -1;
        }
        if (id < 0) {
            return parseEntry(content.substring(start, end), position, lineNumber, file);
        }
        if (digits > start) {
            id = -id;
        }
        int textStart = colon + 1 < end && content.charAt(colon + 1) == ' ' ? colon + 2 : colon + 1;
        if (end - textStart == NO_TEXT.length() && content.startsWith(NO_TEXT, textStart)) {
            return new TlkEntry(id, position, null);
        }
        return new TlkEntry(id, position, unescape(content.substring(textStart, end), textStart - start, lineNumber,
                file));
    }

    /**
     * Parses an entry line.
     *
     * @param line       the line
     * @param position   position to give the entry
     * @param lineNumber 1-based line number, for error messages
     * @param file       the file, for error messages
     * @return the entry
     * @throws IOException if the line is not {@code <id>: <text>} or the text has an invalid
     *                     escape sequence
     */
    private static TlkEntry parseEntry(String line, int position, int lineNumber, Path file) throws IOException {
        int colon = line.indexOf(':');
        if (colon < 0) {
            throw InputFile.error("Expected \"<id>: <text>\" but found " + InputFile.quote(line), lineNumber, file);
        }
        int id = InputFile.parseInt(line.substring(0, colon), "id", lineNumber, file);
        int textStart = colon + 1 < line.length() && line.charAt(colon + 1) == ' ' ? colon + 2 : colon + 1;
        String text = line.substring(textStart);
        if (NO_TEXT.equals(text)) {
            return new TlkEntry(id, position, null);
        }
        return new TlkEntry(id, position, unescape(text, textStart, lineNumber, file));
    }

    /**
     * Decodes the escape sequences of a text.
     *
     * @param text       the escaped text
     * @param offset     index of the text in its line, for the column in error messages
     * @param lineNumber 1-based line number, for error messages
     * @param file       the file, for error messages
     * @return the text
     * @throws IOException if a backslash does not start one of the escapes {@code \\}, {@code \n},
     *                     {@code \r}, {@code \t} or {@code \}{@code uXXXX}
     */
    static String unescape(String text, int offset, int lineNumber, Path file) throws IOException {
        int backslash = text.indexOf('\\');
        if (backslash < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length()).append(text, 0, backslash);
        for (int i = backslash; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                // copy the run up to the next backslash at once
                int next = text.indexOf('\\', i);
                next = next < 0 ? text.length() : next;
                sb.append(text, i, next);
                i = next - 1;
                continue;
            }
            int column = offset + i + 1;
            if (i + 1 >= text.length()) {
                throw InputFile.error("Incomplete escape sequence \"\\\" at the end of the line (write \\\\ for a"
                        + " backslash)", lineNumber, file);
            }
            char next = text.charAt(++i);
            switch (next) {
                case '\\':
                    sb.append('\\');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append(parseHex(text, i + 1, column, lineNumber, file));
                    i += 4;
                    break;
                default:
                    throw InputFile.error("Invalid escape sequence \"\\" + next + "\" at column " + column
                            + (next == 'N' ? " (\\N for \"no text\" must be the whole text)" : "")
                            + " (valid: \\\\ \\n \\r \\t \\uXXXX)", lineNumber, file);
            }
        }
        return sb.toString();
    }

    /**
     * Parses the 4 hex digits of a {@code \}{@code uXXXX} escape.
     *
     * @param text       the escaped text
     * @param start      index of the first digit
     * @param column     1-based column of the backslash, for error messages
     * @param lineNumber 1-based line number, for error messages
     * @param file       the file, for error messages
     * @return the character
     * @throws IOException if there are fewer than 4 hex digits
     */
    private static char parseHex(String text, int start, int column, int lineNumber, Path file) throws IOException {
        int value = 0;
        for (int k = start; k < start + 4; k++) {
            char c = k < text.length() ? text.charAt(k) : 'x';
            int digit = c < 0x80 ? Character.digit(c, 16) : -1;
            if (digit < 0) {
                throw InputFile.error("Invalid escape sequence at column " + column
                        + " (\\u must be followed by 4 hex digits)", lineNumber, file);
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }
}
