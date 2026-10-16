package ru;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Reads a Mass Effect 2 {@code .tlk} string table and writes it out as XML (or plain text): the
 * "Create XML" half of the tool.
 *
 * <p>Usage: {@link #loadTlkData(String)} decodes a file into memory, then
 * {@link #storeToFile(String, FileFormat, DoubleConsumer)} writes it. {@link HuffmanCompression}
 * performs the reverse conversion.
 *
 * <h2>File layout</h2>
 * All integers are 32-bit, signed and little-endian (the PC format; Xbox 360 files are
 * big-endian and rejected). The file consists of four consecutive parts:
 * <ol>
 *   <li><b>Header</b>: 7 ints (28 bytes), see {@link TlkHeader}.</li>
 *   <li><b>String refs</b>: {@code entry1Count + entry2Count} pairs of ints
 *       {@code (stringId, bitOffset)}, see {@link TlkStringRef}. The first {@code entry1Count}
 *       pairs form the male section, the remaining {@code entry2Count} the female section
 *       (gender-specific variants; in the bundled sample every female-section id also occurs in
 *       the male section).</li>
 *   <li><b>Huffman tree</b>: {@code treeNodeCount} pairs of ints {@code (left, right)}, see
 *       {@link HuffmanNode}. Node 0 is the root.</li>
 *   <li><b>Data</b>: {@code dataLen} bytes holding all strings, each Huffman-coded and terminated
 *       by the code for {@code '\0'}, as one continuous bit stream that is read starting at the
 *       least significant bit of each byte (see {@link BitArray#getRev(int)}). The last byte is
 *       zero-padded.</li>
 * </ol>
 * A string ref's {@code bitOffset} is a bit index into that stream, or {@code -1} if the entry has
 * no text. Several refs may share an offset, and an offset may point into the middle of another
 * string to reuse its tail.
 *
 * <h2>XML format</h2>
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
 * returns are written as {@code &#13;} so that XML parsers do not turn them into line feeds.
 */
public class TlkFile {

    /** Value of the {@code TLKToolVersion} attribute written to XML files: the C# original's version. */
    static final String TOOL_VERSION = "1.0.4";
    /** Name of the XML root element. */
    static final String XML_ROOT = "tlkFile";
    /** Name of the root element's attribute holding the version of the tool that wrote the file. */
    static final String XML_VERSION_ATTRIBUTE = "TLKToolVersion";
    /** Name of the XML element of one string ref. */
    static final String XML_STRING = "string";
    /** Name of the XML element holding a string ref's id. */
    static final String XML_ID = "id";
    /** Name of the XML element holding a string ref's position in the file. */
    static final String XML_POSITION = "position";
    /** Name of the XML element holding a string ref's text. */
    static final String XML_DATA = "data";
    /**
     * Optional attribute of {@code <data>} that says whether the entry has text, written only where
     * the id's sign would suggest otherwise (see the class description).
     */
    static final String XML_HAS_TEXT = "hasText";
    /** {@code <data>} content of an entry without text. */
    static final String XML_NO_TEXT = "-1";
    /** Indentation of one XML nesting level. */
    private static final String INDENT = "    ";

    /** Header of the loaded file; {@code null} until {@link #loadTlkData(String)} succeeds. */
    private TlkHeader header;
    /**
     * All string refs of the loaded file in file order (male section, then female), each with
     * its decoded text in {@link TlkStringRef#data}.
     */
    private List<TlkStringRef> stringRefs;
    /** Huffman tree nodes in file order; index 0 is the root. */
    private List<HuffmanNode> characterTree;
    /**
     * The compressed data block as bits; {@link TlkStringRef#bitOffset} values index into it via
     * {@link BitArray#getRev(int)}.
     */
    private BitArray bits;

    /**
     * Creates an empty instance; call {@link #loadTlkData(String)} before
     * {@link #storeToFile(String, FileFormat, DoubleConsumer)}.
     */
    public TlkFile() {
    }

    /**
     * Loads a PC (little-endian) TLK file into memory, replacing anything loaded before. If
     * loading fails, the previously loaded content is kept.
     *
     * <p>The file is read in one pass: header (whose magic number and counts are checked against
     * the file size), string refs, Huffman tree and data block. Then the text of every string ref
     * with a non-negative bit offset is decoded; each distinct offset is decoded once, and an
     * offset into the middle of a string yields just that string's tail. Refs with a negative
     * offset get {@code null} text.
     *
     * @param fileName path of the {@code .tlk} file
     * @throws java.nio.file.NoSuchFileException if the file does not exist
     * @throws EOFException if the file is shorter than its header says
     * @throws IOException  if the file cannot be read, is not a PC TLK file (wrong magic number;
     *                      this includes Xbox 360 files) or is corrupt (negative counts, a tree
     *                      node pointing to a missing node, a string without terminator, ...);
     *                      the message names the file and the problem
     */
    public void loadTlkData(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        long fileSize = Files.size(path);
        try (PositionInputStream in = new PositionInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            TlkHeader newHeader = readHeader(in, fileName, fileSize);

            int refCount = newHeader.entry1Count + newHeader.entry2Count;
            List<TlkStringRef> refs = new ArrayList<>(refCount);
            for (int i = 0; i < refCount; i++) {
                TlkStringRef ref = new TlkStringRef(in);
                ref.position = i;
                refs.add(ref);
            }

            List<HuffmanNode> tree = new ArrayList<>(newHeader.treeNodeCount);
            for (int i = 0; i < newHeader.treeNodeCount; i++) {
                tree.add(new HuffmanNode(in));
            }
            checkTree(tree, fileName);

            byte[] data = new byte[newHeader.dataLen];
            new DataInputStream(in).readFully(data);
            BitArray newBits = new BitArray(data);

            Map<Integer, String> textByOffset = new HashMap<>();
            for (TlkStringRef ref : refs) {
                if (ref.bitOffset >= 0) {
                    String text = textByOffset.get(ref.bitOffset);
                    if (text == null) {
                        text = decodeString(tree, newBits, ref.bitOffset, fileName);
                        textByOffset.put(ref.bitOffset, text);
                    }
                    ref.data = text;
                }
            }

            header = newHeader;
            stringRefs = refs;
            characterTree = tree;
            bits = newBits;
        }
    }

    /**
     * Reads and validates the header.
     *
     * @param in       stream at the start of the file; advanced by {@link TlkHeader#SIZE} bytes
     * @param fileName path of the file, for error messages
     * @param fileSize size of the file in bytes
     * @return the header
     * @throws EOFException if the file is too short for the header or for what the header
     *                      announces
     * @throws IOException  if the magic number is wrong, a count is negative or reading fails
     */
    private static TlkHeader readHeader(InputStream in, String fileName, long fileSize) throws IOException {
        if (fileSize < TlkHeader.SIZE) {
            throw new EOFException("Not a TLK file (too short for a TLK header, " + fileSize + " bytes): " + fileName);
        }
        TlkHeader h = new TlkHeader(in);
        if (h.magic == TlkHeader.XBOX_MAGIC) {
            throw new IOException("Big-endian (Xbox 360) TLK files are not supported, only PC files: " + fileName);
        }
        if (h.magic != TlkHeader.MAGIC) {
            throw new IOException("Not a Mass Effect 2 TLK file (magic number 0x" + Integer.toHexString(h.magic)
                    + " instead of 0x" + Integer.toHexString(TlkHeader.MAGIC) + "): " + fileName);
        }
        if (h.entry1Count < 0 || h.entry2Count < 0 || h.treeNodeCount < 0 || h.dataLen < 0) {
            throw new IOException("Corrupt TLK header (negative count: " + h.entry1Count + " male entries, "
                    + h.entry2Count + " female entries, " + h.treeNodeCount + " tree nodes, "
                    + h.dataLen + " data bytes): " + fileName);
        }
        long expectedSize = h.expectedFileSize();
        if (fileSize < expectedSize) {
            throw new EOFException("Truncated TLK file (the header announces " + expectedSize + " bytes, the file has "
                    + fileSize + "): " + fileName);
        }
        return h;
    }

    /**
     * Checks that every inner-node reference of the tree points to an existing node.
     *
     * @param tree     the nodes, root first
     * @param fileName path of the file, for the error message
     * @throws IOException if a child id is not smaller than the number of nodes
     */
    private static void checkTree(List<HuffmanNode> tree, String fileName) throws IOException {
        for (int i = 0; i < tree.size(); i++) {
            HuffmanNode node = tree.get(i);
            for (int child : new int[]{node.leftNodeId, node.rightNodeId}) {
                if (child >= tree.size()) {
                    throw new IOException("Corrupt Huffman tree (node " + i + " points to node " + child
                            + ", but there are only " + tree.size() + " nodes): " + fileName);
                }
            }
        }
    }

    /**
     * Decodes one NUL-terminated string by walking the Huffman tree.
     *
     * <p>Starting at the root, each bit (read LSB-first with {@link BitArray#getRev(int)}) selects
     * the right child if it is 1 and the left child if it is 0. A non-negative child id is the
     * index of an inner node; a negative id is a leaf for the character
     * {@link #leafCharacter(int)}, after which decoding restarts at the root. The {@code '\0'} leaf
     * ends the string.
     *
     * @param tree      the Huffman tree, root first
     * @param bits      the compressed data
     * @param bitOffset bit index to start decoding at
     * @param fileName  path of the file, for error messages
     * @return the decoded string without its terminator
     * @throws IOException if the tree is empty or the data ends before a terminator
     */
    private static String decodeString(List<HuffmanNode> tree, BitArray bits, int bitOffset, String fileName)
            throws IOException {
        if (tree.isEmpty()) {
            throw new IOException("Corrupt TLK file (it has strings but no Huffman tree): " + fileName);
        }
        HuffmanNode root = tree.get(0);
        HuffmanNode node = root;
        StringBuilder text = new StringBuilder();
        for (int i = bitOffset; i < bits.length(); i++) {
            int next = bits.getRev(i) ? node.rightNodeId : node.leftNodeId;
            if (next >= 0) {
                node = tree.get(next);
            } else {
                char c = leafCharacter(next);
                if (c == '\0') {
                    return text.toString();
                }
                text.append(c);
                node = root;
            }
        }
        throw new IOException("Corrupt TLK data (the string at bit offset " + bitOffset
                + " has no terminator): " + fileName);
    }

    /**
     * Returns the character of a Huffman leaf: leaf ids are {@code -1 - c}, so {@code -1} is the
     * {@code '\0'} terminator and {@code -33} a space. This is what the C# original computes as
     * {@code BitConverter.ToChar(BitConverter.GetBytes(0xffff - id), 0)}, i.e. the low 16 bits of
     * {@code 0xffff - id}.
     *
     * @param leafId a negative child id from the Huffman tree
     * @return the character
     * @see #leafId(char)
     */
    static char leafCharacter(int leafId) {
        return (char) (-1 - leafId);
    }

    /**
     * Returns the Huffman tree child id that stands for a leaf with the given character; the
     * inverse of {@link #leafCharacter(int)}.
     *
     * @param c the character
     * @return {@code -1 - c}, a value from {@code -1} to {@code -65536}
     */
    static int leafId(char c) {
        return -1 - c;
    }

    /**
     * Writes the loaded strings to a text file, replacing any existing file. The file is UTF-8
     * encoded, whatever the platform's default charset.
     *
     * <p>{@link FileFormat#XML} writes the {@code <tlkFile>} document described in the class
     * description, indented with 4 spaces; {@link FileFormat#TXT} and (for now)
     * {@link FileFormat#CSV} write one {@code "<stringId>: <text>"} line per entry, terminated by
     * CRLF ({@code <text>} is {@code null} for entries without text). Entries keep their file order
     * (male section, then female). If writing fails, the output file may be left incomplete.
     *
     * @param fileName path of the output file
     * @param format   output format
     * @param progress receives the completed fraction (0 to 1) as the entries are written, for
     *                 example to move a GUI progress bar; may be {@code null}. It is called on the
     *                 calling thread
     * @throws IllegalStateException if no TLK file has been loaded
     * @throws IOException           if the file cannot be written, or a string contains a
     *                               character that XML 1.0 cannot represent (such as
     *                               {@code U+0001}); the message names the entry
     */
    public void storeToFile(String fileName, FileFormat format, DoubleConsumer progress) throws IOException {
        if (stringRefs == null) {
            throw new IllegalStateException("No TLK file loaded; call loadTlkData first");
        }
        ProgressReporter reporter = new ProgressReporter(progress, stringRefs.size());
        try (Writer out = Files.newBufferedWriter(Paths.get(fileName), StandardCharsets.UTF_8)) {
            switch (format) {
                case XML:
                    writeXml(out, reporter);
                    break;
                case TXT:
                case CSV:
                    writeText(out, reporter);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported output format: " + format);
            }
        }
        reporter.finish();
    }

    /**
     * Writes all string refs as an indented XML document (see the class description). XML
     * comments mark the beginning and end of the male and female sections; they are written even
     * if a section is empty.
     *
     * @param out      destination
     * @param progress advanced once per entry
     * @throws IOException if writing fails or a string cannot be represented in XML
     */
    private void writeXml(Writer out, ProgressReporter progress) throws IOException {
        int maleCount = header.entry1Count;
        int lastPosition = header.entry1Count + header.entry2Count - 1;
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<" + XML_ROOT + " " + XML_VERSION_ATTRIBUTE + "=\"" + TOOL_VERSION + "\">\n");
        writeComment(out, "Male entries section begin (ends at position " + (maleCount - 1) + ")");
        int index = 0;
        for (TlkStringRef ref : stringRefs) {
            if (index++ == maleCount) {
                writeSectionChange(out, lastPosition);
            }
            writeXmlEntry(out, ref);
            progress.step();
        }
        if (index <= maleCount) {
            writeSectionChange(out, lastPosition);
        }
        writeComment(out, "Female entries section end");
        out.write("</" + XML_ROOT + ">\n");
    }

    /**
     * Writes the comments that close the male section and open the female one.
     *
     * @param out          destination
     * @param lastPosition position of the last entry of the female section
     * @throws IOException if writing fails
     */
    private static void writeSectionChange(Writer out, int lastPosition) throws IOException {
        writeComment(out, "Male entries section end");
        writeComment(out, "Female entries section begin (ends at position " + lastPosition + ")");
    }

    /**
     * Writes an XML comment on its own line, indented by one level.
     *
     * @param out  destination
     * @param text comment text; must not contain {@code "--"}
     * @throws IOException if writing fails
     */
    private static void writeComment(Writer out, String text) throws IOException {
        out.write(INDENT + "<!--" + text + "-->\n");
    }

    /**
     * Writes one {@code <string>} element.
     *
     * @param out destination
     * @param ref the entry
     * @throws IOException if writing fails or the text cannot be represented in XML
     */
    private static void writeXmlEntry(Writer out, TlkStringRef ref) throws IOException {
        boolean hasText = ref.data != null;
        out.write(INDENT + "<" + XML_STRING + ">\n");
        out.write(INDENT + INDENT + "<" + XML_ID + ">" + ref.stringId + "</" + XML_ID + ">\n");
        out.write(INDENT + INDENT + "<" + XML_POSITION + ">" + ref.position + "</" + XML_POSITION + ">\n");
        out.write(INDENT + INDENT + "<" + XML_DATA);
        if (hasText != (ref.stringId >= 0)) {
            out.write(" " + XML_HAS_TEXT + "=\"" + hasText + "\"");
        }
        out.write(">");
        if (hasText) {
            writeEscaped(out, ref);
        } else {
            out.write(XML_NO_TEXT);
        }
        out.write("</" + XML_DATA + ">\n");
        out.write(INDENT + "</" + XML_STRING + ">\n");
    }

    /**
     * Writes the text of an entry as XML character data: {@code &}, {@code <} and {@code >}
     * become entity references, a carriage return becomes {@code &#13;}, everything else is
     * written as is.
     *
     * @param out destination
     * @param ref the entry whose {@link TlkStringRef#data} to write
     * @throws IOException if writing fails or the text contains a character XML 1.0 does not
     *                     allow (a control character other than tab, line feed and carriage
     *                     return, an unpaired surrogate, {@code U+FFFE} or {@code U+FFFF})
     */
    private static void writeEscaped(Writer out, TlkStringRef ref) throws IOException {
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
            out.write(text, start, i - start);
            out.write(replacement);
            start = i + 1;
        }
        out.write(text, start, text.length() - start);
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
     * Writes all string refs as plain text: one {@code "<stringId>: <data>"} line per entry,
     * terminated by CRLF ({@code data} prints as {@code null} for entries without text).
     *
     * @param out      destination
     * @param progress advanced once per entry
     * @throws IOException if writing fails
     */
    private void writeText(Writer out, ProgressReporter progress) throws IOException {
        for (TlkStringRef ref : stringRefs) {
            out.write(ref.stringId + ": " + ref.data + "\r\n");
            progress.step();
        }
    }

    /**
     * Reads a 32-bit signed integer stored little-endian (least significant byte first), the
     * byte order of every integer in a PC TLK file.
     *
     * @param in stream positioned at the integer; advanced by 4 bytes
     * @return the integer
     * @throws IOException  if the stream fails
     * @throws EOFException if the stream ends before 4 bytes were read
     */
    public static int readInt32(InputStream in) throws IOException {
        int ch1 = in.read();
        int ch2 = in.read();
        int ch3 = in.read();
        int ch4 = in.read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException("End of stream inside a 4-byte integer");
        }
        return (ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1;
    }

    /**
     * Writes a 32-bit signed integer little-endian (least significant byte first); the inverse of
     * {@link #readInt32(InputStream)}.
     *
     * @param out   destination
     * @param value the integer
     * @throws IOException if writing fails
     */
    public static void writeInt32(OutputStream out, int value) throws IOException {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    /**
     * The fixed 28-byte header at the start of a TLK file: seven little-endian ints, in the
     * order of the fields below.
     */
    public static class TlkHeader {
        /** Size of the header in bytes. */
        public static final int SIZE = 7 * Integer.BYTES;
        /** Magic number of a PC TLK file: {@code 0x006B6C54}, the bytes {@code "Tlk\0"}. */
        public static final int MAGIC = 7040084;
        /** What {@link #MAGIC} reads as in a big-endian (Xbox 360) file. */
        public static final int XBOX_MAGIC = 1416391424;
        /** {@link #ver} of ME2 files, and the value {@link HuffmanCompression} writes. */
        public static final int VERSION = 3;
        /** {@link #minVer} of ME2 files, and the value {@link HuffmanCompression} writes. */
        public static final int MIN_VERSION = 2;

        /** File signature; {@link #MAGIC} for a PC file. */
        public int magic;
        /** Format version; {@link #VERSION} in ME2 files. Not validated. */
        public int ver;
        /** Minimum version; {@link #MIN_VERSION} in ME2 files. Not validated. */
        public int minVer;
        /** Number of string refs in the first (male) section. */
        public int entry1Count;
        /** Number of string refs in the second (female) section, which follows the male one. */
        public int entry2Count;
        /** Number of Huffman tree nodes (8 bytes each), stored after the string refs. */
        public int treeNodeCount;
        /** Length in bytes of the compressed data block, stored after the tree. */
        public int dataLen;

        /**
         * Reads the seven header fields.
         *
         * @param r stream positioned at the start of the file; advanced by 28 bytes
         * @throws IOException if reading fails or the stream ends inside the header
         */
        public TlkHeader(InputStream r) throws IOException {
            this.magic = readInt32(r);
            this.ver = readInt32(r);
            this.minVer = readInt32(r);
            this.entry1Count = readInt32(r);
            this.entry2Count = readInt32(r);
            this.treeNodeCount = readInt32(r);
            this.dataLen = readInt32(r);
        }

        /**
         * Creates the header of a new PC file, with {@link #MAGIC}, {@link #VERSION} and
         * {@link #MIN_VERSION}.
         *
         * @param entry1Count   number of string refs in the male section
         * @param entry2Count   number of string refs in the female section
         * @param treeNodeCount number of Huffman tree nodes
         * @param dataLen       length of the data block in bytes
         */
        public TlkHeader(int entry1Count, int entry2Count, int treeNodeCount, int dataLen) {
            this.magic = MAGIC;
            this.ver = VERSION;
            this.minVer = MIN_VERSION;
            this.entry1Count = entry1Count;
            this.entry2Count = entry2Count;
            this.treeNodeCount = treeNodeCount;
            this.dataLen = dataLen;
        }

        /**
         * Returns the size of a file with this header: header, string refs, tree and data.
         *
         * @return the size in bytes
         */
        public long expectedFileSize() {
            return SIZE + 8L * entry1Count + 8L * entry2Count + 8L * treeNodeCount + dataLen;
        }

        /**
         * Writes the seven fields, little-endian.
         *
         * @param out destination; advanced by 28 bytes
         * @throws IOException if writing fails
         */
        public void write(OutputStream out) throws IOException {
            for (int value : new int[]{magic, ver, minVer, entry1Count, entry2Count, treeNodeCount, dataLen}) {
                writeInt32(out, value);
            }
        }
    }

    /**
     * One string-ref entry: an 8-byte record pairing a string id with the position of its text in
     * the compressed data, plus two fields filled in while loading.
     */
    public static class TlkStringRef {
        /**
         * The id the game uses to look the string up. In the bundled {@code BIOGame_RUS.tlk},
         * the entries with a negative id (top bit set) are exactly those with a
         * {@link #bitOffset} of {@code -1}, i.e. without text.
         */
        public int stringId;
        /**
         * Bit index into the compressed data where the text starts, or {@code -1} (any negative
         * value) if the entry has no text.
         */
        public int bitOffset;

        /**
         * Decoded text without the NUL terminator; {@code null} if {@link #bitOffset} is negative.
         * Not stored in the file; set by {@link TlkFile#loadTlkData(String)}.
         */
        public String data;
        /**
         * Zero-based index of this entry in the file's string-ref table, counted across both
         * sections: positions {@code 0} to {@code entry1Count - 1} are the male section, the
         * following {@code entry2Count} the female one. Not stored in the file; written to XML as
         * {@code <position>} so that {@link HuffmanCompression} can restore the original order.
         */
        public int position;

        /**
         * Reads the id and bit offset of one entry; {@link #data} and {@link #position} are left
         * for the caller to fill in.
         *
         * @param r stream positioned at the entry; advanced by 8 bytes
         * @throws IOException if reading fails or the stream ends inside the entry
         */
        public TlkStringRef(InputStream r) throws IOException {
            this.stringId = readInt32(r);
            this.bitOffset = readInt32(r);
        }

        /**
         * Creates an entry to be written.
         *
         * @param stringId  the string id
         * @param bitOffset bit index of the text in the data block, or {@code -1} for no text
         */
        public TlkStringRef(int stringId, int bitOffset) {
            this.stringId = stringId;
            this.bitOffset = bitOffset;
        }

        /**
         * Writes the id and the bit offset, little-endian.
         *
         * @param out destination; advanced by 8 bytes
         * @throws IOException if writing fails
         */
        public void write(OutputStream out) throws IOException {
            writeInt32(out, stringId);
            writeInt32(out, bitOffset);
        }
    }

    /**
     * One node of the Huffman tree as stored in the file: two child references of 4 bytes each.
     *
     * <p>A child value {@code >= 0} is the index of another node; a negative value {@code v} is a
     * leaf for the character {@code (char) (-1 - v)} (see {@link TlkFile#leafCharacter(int)}), so
     * {@code -1} is the {@code '\0'} terminator and {@code -33} is a space. While decoding, a 0
     * bit follows {@link #leftNodeId} and a 1 bit follows {@link #rightNodeId}.
     */
    public static class HuffmanNode {
        /** Child taken on a 0 bit: node index, or {@code -1 - c} for a leaf with character {@code c}. */
        public int leftNodeId;
        /** Child taken on a 1 bit: node index, or {@code -1 - c} for a leaf with character {@code c}. */
        public int rightNodeId;

        /**
         * Reads one node: the left child, then the right child.
         *
         * @param r stream positioned at the node; advanced by 8 bytes
         * @throws IOException if reading fails or the stream ends inside the node
         */
        public HuffmanNode(InputStream r) throws IOException {
            this.leftNodeId = readInt32(r);
            this.rightNodeId = readInt32(r);
        }

        /**
         * Creates a node to be written.
         *
         * @param leftNodeId  child for a 0 bit
         * @param rightNodeId child for a 1 bit
         */
        public HuffmanNode(int leftNodeId, int rightNodeId) {
            this.leftNodeId = leftNodeId;
            this.rightNodeId = rightNodeId;
        }

        /**
         * Writes the left and the right child, little-endian.
         *
         * @param out destination; advanced by 8 bytes
         * @throws IOException if writing fails
         */
        public void write(OutputStream out) throws IOException {
            writeInt32(out, leftNodeId);
            writeInt32(out, rightNodeId);
        }
    }
}
