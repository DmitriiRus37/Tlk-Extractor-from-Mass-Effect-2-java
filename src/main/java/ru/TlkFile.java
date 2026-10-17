package ru;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.stream.IntStream;

/**
 * Reads a Mass Effect 2 {@code .tlk} string table and writes it out as XML, TXT or CSV: the
 * "Create XML/TXT/CSV" half of the tool.
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
 * <h2>Text formats</h2>
 * {@link #storeToFile(String, FileFormat, DoubleConsumer)} writes the string refs as
 * {@link FileFormat#XML} ({@link XmlFormat}), {@link FileFormat#TXT} ({@link TxtFormat}) or
 * {@link FileFormat#CSV} ({@link CsvFormat}). All three carry the same information: the tool
 * version, and for each string ref in file order its id, its position, whether it has text and
 * the text. {@link HuffmanCompression} reads each of them back.
 */
public class TlkFile {

    /**
     * Tool version written to every exported file (the {@code TLKToolVersion}): the C# original's
     * version.
     */
    static final String TOOL_VERSION = "1.0.4";
    /**
     * Largest part of a TLK file after the header that is read: the most a byte array can hold.
     */
    private static final long MAX_BODY_SIZE = Integer.MAX_VALUE - 8;
    /** Number of entries {@link #storeToFile(String, FileFormat, DoubleConsumer)} formats per task. */
    private static final int EXPORT_CHUNK = 2048;
    /**
     * Characters an XML entry has besides its text, for sizing export buffers: the indented
     * {@code <string>}, {@code <id>}, {@code <position>} and {@code <data>} lines, with an 11-digit
     * id and a 6-digit position, take about 114. Only an estimate: a buffer grows if it is short.
     */
    private static final int XML_MARKUP_CHARS = 112;
    /**
     * Characters a TXT or CSV entry has besides its text, for sizing export buffers: id,
     * {@code ": "} and CRLF in TXT (about 15), id, position, {@code true}, commas, quotes and CRLF
     * in CSV (up to about 29).
     */
    private static final int TEXT_MARKUP_CHARS = 32;

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
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            TlkHeader newHeader = readHeader(in, fileName, fileSize);

            // the rest of what the header announces in one read, then parsed from memory
            long bodySize = newHeader.expectedFileSize() - TlkHeader.SIZE;
            if (bodySize > MAX_BODY_SIZE) {
                throw new IOException("TLK file too large (the header announces " + newHeader.expectedFileSize()
                        + " bytes): " + fileName);
            }
            byte[] body = new byte[(int) bodySize];
            in.readFully(body);
            ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);

            int refCount = newHeader.entry1Count + newHeader.entry2Count;
            int[] refInts = new int[2 * refCount];
            buffer.asIntBuffer().get(refInts);
            buffer.position(buffer.position() + Integer.BYTES * refInts.length);
            List<TlkStringRef> refs = new ArrayList<>(refCount);
            for (int i = 0; i < refCount; i++) {
                TlkStringRef ref = new TlkStringRef(refInts[2 * i], refInts[2 * i + 1]);
                ref.position = i;
                refs.add(ref);
            }

            List<HuffmanNode> tree = new ArrayList<>(newHeader.treeNodeCount);
            for (int i = 0; i < newHeader.treeNodeCount; i++) {
                tree.add(new HuffmanNode(buffer.getInt(), buffer.getInt()));
            }
            checkTree(tree, fileName);

            byte[] data = Arrays.copyOfRange(body, buffer.position(), body.length);
            BitArray newBits = BitArray.wrap(data);

            decodeStrings(refs, tree, data, fileName);

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
     * Sets the text of every ref with a non-negative bit offset, decoding each distinct offset
     * once with {@link HuffmanDecoder}, which walks the Huffman tree from the root: a 1 bit (read
     * LSB-first, as {@link BitArray#getRev(int)} does) selects the right child, a 0 bit the left
     * one, and a negative child id is a leaf for the character {@link #leafCharacter(int)}, after which
     * decoding restarts at the root. The {@code '\0'} leaf ends a string.
     *
     * <p>The strings are independent, so they are decoded in parallel ({@link Workers}). Errors
     * are reported as a sequential decoder would: for the first offset in ref order that fails.
     *
     * @param refs     the string refs
     * @param tree     the Huffman tree, root first, already checked with {@link #checkTree}
     * @param data     the data block
     * @param fileName path of the file, for error messages
     * @throws IOException if there are strings but no tree, or a string has no terminator
     */
    private static void decodeStrings(List<TlkStringRef> refs, List<HuffmanNode> tree, byte[] data, String fileName)
            throws IOException {
        // the distinct offsets in order of first occurrence, and the index of each ref's offset
        IntIndex offsets = new IntIndex(refs.size());
        int[] refText = new int[refs.size()];
        for (int i = 0; i < refText.length; i++) {
            int offset = refs.get(i).bitOffset;
            refText[i] = offset < 0 ? -1 : offsets.add(offset);
        }
        if (offsets.size() == 0) {
            return;
        }
        if (tree.isEmpty()) {
            throw new IOException("Corrupt TLK file (it has strings but no Huffman tree): " + fileName);
        }
        HuffmanDecoder decoder = new HuffmanDecoder(tree, data);
        String[] texts = new String[offsets.size()];
        // a parallel stream started inside a ForkJoinPool runs in that pool
        Workers.pool().submit(() -> IntStream.range(0, texts.length).parallel().forEach(i -> {
            StringBuilder text = new StringBuilder();
            if (decoder.decode(offsets.key(i), text)) {
                texts[i] = text.toString();
            }
        })).join();
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] == null) {
                throw new IOException("Corrupt TLK data (the string at bit offset " + offsets.key(i)
                        + " has no terminator): " + fileName);
            }
        }
        for (int i = 0; i < refText.length; i++) {
            if (refText[i] >= 0) {
                refs.get(i).data = texts[refText[i]];
            }
        }
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
     * <p>{@link FileFormat#XML} writes the {@code <tlkFile>} document described in
     * {@link XmlFormat}, {@link FileFormat#TXT} the {@code "<id>: <text>"} lines described in
     * {@link TxtFormat} and {@link FileFormat#CSV} the RFC 4180 table described in
     * {@link CsvFormat}. Entries keep their file order (male section, then female); each format
     * records the tool version {@value #TOOL_VERSION}, every entry's id and position, and whether
     * it has text. If writing fails, the output file may be left incomplete.
     *
     * @param fileName path of the output file
     * @param format   output format
     * @param progress receives the completed fraction (0 to 1) as the entries are written, for
     *                 example to move a GUI progress bar; may be {@code null}. It is called on the
     *                 calling thread
     * @throws IllegalStateException if no TLK file has been loaded
     * @throws IOException           if the file cannot be written, or a string contains a
     *                               character that the format cannot represent (for XML a
     *                               character XML 1.0 does not allow, such as {@code U+0001}; for
     *                               CSV an unpaired surrogate); the message names the entry
     */
    public void storeToFile(String fileName, FileFormat format, DoubleConsumer progress) throws IOException {
        if (stringRefs == null) {
            throw new IllegalStateException("No TLK file loaded; call loadTlkData first");
        }
        ProgressReporter reporter = new ProgressReporter(progress, stringRefs.size());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(Paths.get(fileName)))) {
            write(format, out, reporter);
        }
        reporter.finish();
    }

    /**
     * Returns the writer of a format.
     *
     * @param format the format
     * @param out    destination
     * @return the writer
     */
    private static FormatWriter formatWriter(FileFormat format, Writer out) {
        return switch (format) {
            case XML -> XmlFormat.writer(out);
            case TXT -> TxtFormat.writer(out);
            case CSV -> CsvFormat.writer(out);
        };
    }

    /**
     * Writes all string refs in a format: the header, then the entries in file order. Comments
     * mark the beginning and end of the male and female sections; they are written even if a
     * section is empty.
     *
     * <p>The entries are formatted and UTF-8 encoded in parallel ({@link Workers}),
     * {@value #EXPORT_CHUNK} at a time, while the calling thread writes the finished chunks in order and reports the
     * progress, so the file and the error for a string that cannot be written are the same as
     * with a sequential export.
     *
     * @param format   the format
     * @param out      destination
     * @param progress advanced once per entry, on the calling thread
     * @throws IOException if writing fails or a string cannot be represented in the format
     */
    private void write(FileFormat format, OutputStream out, ProgressReporter progress) throws IOException {
        int maleCount = header.entry1Count;
        int lastPosition = header.entry1Count + header.entry2Count - 1;
        TlkStringRef[] refs = stringRefs.toArray(new TlkStringRef[0]);

        TextBuffer head = new TextBuffer();
        FormatWriter writer = formatWriter(format, head);
        writer.begin(TOOL_VERSION);
        writer.comment("Male entries section begin (ends at position " + (maleCount - 1) + ")");
        write(out, head.encode());

        List<CompletableFuture<ByteBuffer>> chunks = new ArrayList<>();
        for (int from = 0; from < refs.length; from += EXPORT_CHUNK) {
            int start = from;
            int end = Math.min(refs.length, from + EXPORT_CHUNK);
            chunks.add(CompletableFuture.supplyAsync(() -> formatChunk(format, refs, start, end, maleCount, lastPosition),
                    Workers.pool()));
        }
        try {
            for (int i = 0; i < chunks.size(); i++) {
                write(out, Workers.join(chunks.get(i)));
                for (int k = i * EXPORT_CHUNK; k < Math.min(refs.length, (i + 1) * EXPORT_CHUNK); k++) {
                    progress.step();
                }
            }
        } finally {
            // after a failure, chunks not yet started are not needed any more
            chunks.forEach(chunk -> chunk.cancel(false));
        }

        TextBuffer tail = new TextBuffer();
        writer = formatWriter(format, tail);
        if (refs.length <= maleCount) {
            writeSectionChange(writer, lastPosition);
        }
        writer.comment("Female entries section end");
        writer.end();
        write(out, tail.encode());
    }

    /**
     * Formats and encodes the entries {@code start} to {@code end - 1}, with the section change
     * comments if the female section starts among them.
     *
     * @param format       the format
     * @param refs         all string refs
     * @param start        index of the first entry
     * @param end          index after the last entry
     * @param maleCount    number of entries in the male section
     * @param lastPosition position of the last entry of the female section
     * @return the UTF-8 bytes
     * @throws UncheckedIOException wrapping the {@link IOException} for a string that cannot be
     *                              represented in the format
     */
    private static ByteBuffer formatChunk(FileFormat format, TlkStringRef[] refs, int start, int end, int maleCount,
                                          int lastPosition) {
        // room for the texts plus the markup around them, so that the buffer rarely has to grow
        int markup = format == FileFormat.XML ? XML_MARKUP_CHARS : TEXT_MARKUP_CHARS;
        long capacity = 256 + (long) (end - start) * markup;
        for (int i = start; i < end; i++) {
            if (refs[i].data != null) {
                capacity += refs[i].data.length();
            }
        }
        TextBuffer text = new TextBuffer((int) Math.min(capacity, MAX_BODY_SIZE));
        FormatWriter writer = formatWriter(format, text);
        try {
            for (int i = start; i < end; i++) {
                if (i == maleCount) {
                    writeSectionChange(writer, lastPosition);
                }
                writer.entry(refs[i]);
            }
            return text.encode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the remaining bytes of a buffer.
     *
     * @param out   destination
     * @param bytes the bytes, backed by an array
     * @throws IOException if writing fails
     */
    private static void write(OutputStream out, ByteBuffer bytes) throws IOException {
        out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
    }

    /**
     * Writes the comments that close the male section and open the female one.
     *
     * @param writer       the format
     * @param lastPosition position of the last entry of the female section
     * @throws IOException if writing fails
     */
    private static void writeSectionChange(FormatWriter writer, int lastPosition) throws IOException {
        writer.comment("Male entries section end");
        writer.comment("Female entries section begin (ends at position " + lastPosition + ")");
    }

    /** A {@link Writer} that collects text in a char array, without locking. */
    private static final class TextBuffer extends Writer {
        /** The text written so far, in its first {@link #length} elements. */
        private char[] chars;
        /** Number of characters written. */
        private int length;

        /** Creates a buffer with a small initial capacity. */
        TextBuffer() {
            this(256);
        }

        /**
         * Creates a buffer.
         *
         * @param capacity expected number of characters; more are accepted
         */
        TextBuffer(int capacity) {
            chars = new char[Math.max(16, capacity)];
        }

        /**
         * Grows the array so that {@code extra} more characters fit.
         *
         * @param extra number of characters about to be written
         */
        private void ensureRoom(int extra) {
            int needed = length + extra;
            if (needed > chars.length) {
                chars = Arrays.copyOf(chars, Math.max(needed, 2 * chars.length));
            }
        }

        @Override
        public void write(int c) {
            ensureRoom(1);
            chars[length++] = (char) c;
        }

        @Override
        public void write(char[] c, int offset, int count) {
            ensureRoom(count);
            System.arraycopy(c, offset, chars, length, count);
            length += count;
        }

        @Override
        public void write(String s, int offset, int count) {
            ensureRoom(count);
            s.getChars(offset, offset + count, chars, length);
            length += count;
        }

        @Override
        public Writer append(CharSequence s) {
            if (s instanceof StringBuilder sb) {
                ensureRoom(sb.length());
                sb.getChars(0, sb.length(), chars, length);
                length += sb.length();
            } else {
                String str = String.valueOf(s);
                write(str, 0, str.length());
            }
            return this;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        /**
         * Encodes the text as UTF-8, failing on unpaired surrogates like the writer of
         * {@link Files#newBufferedWriter(Path, java.nio.charset.Charset, java.nio.file.OpenOption...)}.
         *
         * @return the bytes, backed by an array
         * @throws CharacterCodingException if the text contains an unpaired surrogate
         */
        ByteBuffer encode() throws CharacterCodingException {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            // a UTF-16 char takes at most 3 UTF-8 bytes (a surrogate pair takes 4 for 2 chars), so
            // the output never has to grow
            ByteBuffer out = ByteBuffer.allocate((int) Math.max(16, Math.min(3L * length, MAX_BODY_SIZE)));
            CoderResult result = encoder.encode(CharBuffer.wrap(chars, 0, length), out, true);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            result = encoder.flush(out);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            return out.flip();
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
         * following {@code entry2Count} the female one. Not stored in the file; exported (as XML
         * {@code <position>}, CSV column or TXT line order) so that {@link HuffmanCompression}
         * can restore the original order.
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
     * bit follows {@link #leftNodeId()} and a 1 bit follows {@link #rightNodeId()}.
     *
     * @param leftNodeId  child taken on a 0 bit: node index, or {@code -1 - c} for a leaf with
     *                    character {@code c}
     * @param rightNodeId child taken on a 1 bit: node index, or {@code -1 - c} for a leaf with
     *                    character {@code c}
     */
    public record HuffmanNode(int leftNodeId, int rightNodeId) {

        /**
         * Reads one node: the left child, then the right child.
         *
         * @param r stream positioned at the node; advanced by 8 bytes
         * @throws IOException if reading fails or the stream ends inside the node
         */
        public HuffmanNode(InputStream r) throws IOException {
            this(readInt32(r), readInt32(r));
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
