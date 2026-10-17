package ru;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A fast reader for XML files laid out exactly as {@link XmlFormat} writes them; the StAX reader
 * of {@link XmlFormat} reads everything else and reports every error.
 *
 * <p>It accepts the XML declaration {@link XmlFormat} writes, the {@code <tlkFile>} root with
 * only its {@code TLKToolVersion} attribute, comments between the {@code <string>} elements, and
 * {@code <string>} elements with exactly an {@code <id>}, a {@code <position>} and a
 * {@code <data>} child in that order, separated by spaces, tabs and line feeds only. Integers have
 * no sign other than {@code -} and no surrounding space; text may contain only the references
 * {@code &amp; &lt; &gt; &#13;}, and an entry with a negative id must have a negative number as
 * its {@code <data>}. Anything else, including a byte order mark, a carriage return (which an
 * XML parser turns into a line feed), CDATA, other references, a {@code hasText} attribute,
 * {@code ]]>} or a character XML does not allow, makes it give up, and the file goes to StAX. For
 * the files it accepts it returns what StAX would, which a fuzz test checks.
 *
 * <p>On {@code BIOGame_RUS.tlk} exported as XML (18 MB) this cut
 * {@link HuffmanCompression#loadInputData} from ~243 to ~135 ms cold and from ~124 to ~56 ms warm.
 */
final class FastXml {

    /** Signals that the document is not in the layout this reader accepts; carries no stack trace. */
    private static final class NotCanonical extends Exception {
        /** The only instance: it is thrown for control flow only. */
        static final NotCanonical INSTANCE = new NotCanonical();

        /** Creates the instance, without stack trace or suppression. */
        private NotCanonical() {
            super(null, null, false, false);
        }
    }

    /** The decoded document. */
    private final char[] chars;
    /** Number of characters in {@link #chars}. */
    private final int end;
    /** Index of the next character to read. */
    private int pos;

    /**
     * Creates a reader.
     *
     * @param chars the decoded document
     * @param end   number of characters
     */
    private FastXml(char[] chars, int end) {
        this.chars = chars;
        this.end = end;
    }

    /**
     * Reads a file if it is laid out exactly as {@link XmlFormat} writes it.
     *
     * @param bytes the content of the file
     * @return its version and entries, or {@code null} if it is laid out differently, is not
     *         valid UTF-8 or is malformed
     */
    static InputFile read(byte[] bytes) {
        CharBuffer decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            return null;
        }
        if (!decoded.hasArray() || decoded.arrayOffset() != 0 || decoded.position() != 0) {
            return null;
        }
        try {
            return new FastXml(decoded.array(), decoded.limit()).document();
        } catch (NotCanonical e) {
            return null;
        }
    }

    /**
     * Reads the whole document.
     *
     * @return its version and entries
     * @throws NotCanonical if it is not laid out as expected
     */
    private InputFile document() throws NotCanonical {
        expect("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        skipSpace();
        expect("<" + XmlFormat.ROOT + " " + XmlFormat.VERSION_ATTRIBUTE + "=\"");
        int versionStart = pos;
        while (pos < end && chars[pos] != '"') {
            char c = chars[pos++];
            if (c == '<' || c == '&' || c <= ' ' || c >= 0x7F) {
                throw NotCanonical.INSTANCE;
            }
        }
        String version = new String(chars, versionStart, pos - versionStart);
        expect("\">");
        try {
            HuffmanCompression.checkToolVersion(version, "XML", "", null);
        } catch (IOException e) {
            throw NotCanonical.INSTANCE;
        }

        List<TlkEntry> entries = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (true) {
            skipSpace();
            if (pos + 1 >= end || chars[pos] != '<') {
                throw NotCanonical.INSTANCE;
            }
            char next = chars[pos + 1];
            if (next == '!') {
                comment();
            } else if (next == 's') {
                entries.add(entry(text));
            } else if (next == '/') {
                break;
            } else {
                throw NotCanonical.INSTANCE;
            }
        }
        expect("</" + XmlFormat.ROOT + ">");
        while (true) {
            skipSpace();
            if (pos >= end) {
                return new InputFile(version, entries);
            }
            if (pos + 1 < end && chars[pos] == '<' && chars[pos + 1] == '!') {
                comment();
            } else {
                throw NotCanonical.INSTANCE;
            }
        }
    }

    /**
     * Reads one {@code <string>} element.
     *
     * @param text scratch buffer
     * @return the entry
     * @throws NotCanonical if it is not laid out as expected, or has no text but a {@code <data>}
     *                      that is not a negative number
     */
    private TlkEntry entry(StringBuilder text) throws NotCanonical {
        expect("<" + XmlFormat.STRING + ">");
        skipSpace();
        expect("<" + XmlFormat.ID + ">");
        int id = integer();
        expect("</" + XmlFormat.ID + ">");
        skipSpace();
        expect("<" + XmlFormat.POSITION + ">");
        int position = integer();
        expect("</" + XmlFormat.POSITION + ">");
        skipSpace();
        expect("<" + XmlFormat.DATA + ">");
        String data = text(text);
        expect("</" + XmlFormat.DATA + ">");
        skipSpace();
        expect("</" + XmlFormat.STRING + ">");
        if (id >= 0) {
            return new TlkEntry(id, position, data);
        }
        // without a hasText attribute, a negative id means no text: <data> must be negative
        try {
            if (Integer.parseInt(data) < 0) {
                return new TlkEntry(id, position, null);
            }
        } catch (NumberFormatException e) {
            // StAX accepts surrounding space and reports the errors
        }
        throw NotCanonical.INSTANCE;
    }

    /**
     * Reads an integer: an optional {@code -} and 1 to 10 digits, within the {@code int} range.
     *
     * @return the value
     * @throws NotCanonical if there is no such integer
     */
    private int integer() throws NotCanonical {
        boolean negative = pos < end && chars[pos] == '-';
        if (negative) {
            pos++;
        }
        int digitsStart = pos;
        long value = 0;
        while (pos < end && chars[pos] >= '0' && chars[pos] <= '9' && pos - digitsStart < 11) {
            value = 10 * value + (chars[pos++] - '0');
        }
        int digits = pos - digitsStart;
        value = negative ? -value : value;
        if (digits == 0 || digits > 10 || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw NotCanonical.INSTANCE;
        }
        return (int) value;
    }

    /**
     * Reads character data up to the next {@code <}, resolving the references {@link XmlFormat}
     * writes.
     *
     * @param buffer scratch buffer
     * @return the text
     * @throws NotCanonical if it holds another reference, {@code ]]>} or a character the reader
     *                      does not accept
     */
    private String text(StringBuilder buffer) throws NotCanonical {
        int start = pos;
        int i = pos;
        // the common case: plain characters up to the end tag, returned without copying twice
        while (i < end && plain(chars[i]) && chars[i] != '>') {
            i++;
        }
        if (i < end && chars[i] == '<') {
            pos = i;
            return new String(chars, start, i - start);
        }
        buffer.setLength(0);
        buffer.append(chars, start, i - start);
        while (true) {
            if (i >= end) {
                throw NotCanonical.INSTANCE;
            }
            char c = chars[i];
            if (c == '<') {
                break;
            }
            if (c == '&') {
                i = reference(i, buffer);
                continue;
            }
            if (c == '>' ? i - start >= 2 && chars[i - 1] == ']' && chars[i - 2] == ']' : !plain(c)) {
                throw NotCanonical.INSTANCE;
            }
            buffer.append(c);
            i++;
        }
        pos = i;
        return buffer.toString();
    }

    /**
     * Resolves one of the references {@link XmlFormat} writes.
     *
     * @param at     index of the {@code &}
     * @param buffer receives the character
     * @return the index after the reference
     * @throws NotCanonical if it is another reference
     */
    private int reference(int at, StringBuilder buffer) throws NotCanonical {
        if (matches(at, "&amp;")) {
            buffer.append('&');
            return at + 5;
        } else if (matches(at, "&lt;")) {
            buffer.append('<');
            return at + 4;
        } else if (matches(at, "&gt;")) {
            buffer.append('>');
            return at + 4;
        } else if (matches(at, "&#13;")) {
            buffer.append('\r');
            return at + 5;
        }
        throw NotCanonical.INSTANCE;
    }

    /**
     * Tells whether a character may appear as is in text this reader accepts: not markup
     * ({@code <}, {@code &}), not a carriage return (an XML parser turns it into a line feed) and
     * allowed by XML 1.0 (tab, line feed and from space up, except {@code U+FFFE} and
     * {@code U+FFFF}; strict UTF-8 decoding already rules out unpaired surrogates).
     *
     * @param c the character
     * @return {@code true} if it is copied as is
     */
    private static boolean plain(char c) {
        return c >= ' ' ? c != '<' && c != '&' && c < 0xFFFE : c == '\t' || c == '\n';
    }

    /**
     * Skips a comment.
     *
     * @throws NotCanonical if it is not closed, contains {@code --} or a character XML does not
     *                      allow
     */
    private void comment() throws NotCanonical {
        expect("<!--");
        while (pos + 1 < end && !(chars[pos] == '-' && chars[pos + 1] == '-')) {
            char c = chars[pos++];
            if (c < ' ' ? c != '\t' && c != '\n' : c >= 0xFFFE) {
                throw NotCanonical.INSTANCE;
            }
        }
        expect("-->");
    }

    /**
     * Tells whether a string occurs at an index.
     *
     * @param at the index
     * @param s  the string
     * @return {@code true} if it does
     */
    private boolean matches(int at, String s) {
        if (at + s.length() > end) {
            return false;
        }
        for (int k = 0; k < s.length(); k++) {
            if (chars[at + k] != s.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a string that must come next.
     *
     * @param s the string
     * @throws NotCanonical if something else comes next
     */
    private void expect(String s) throws NotCanonical {
        if (!matches(pos, s)) {
            throw NotCanonical.INSTANCE;
        }
        pos += s.length();
    }

    /** Skips spaces, tabs and line feeds. */
    private void skipSpace() {
        while (pos < end && (chars[pos] == ' ' || chars[pos] == '\n' || chars[pos] == '\t')) {
            pos++;
        }
    }
}
