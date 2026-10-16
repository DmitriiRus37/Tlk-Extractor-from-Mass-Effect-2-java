package ru;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * Builds a Mass Effect 2 {@code .tlk} file from the XML, TXT or CSV file written by
 * {@link TlkFile}: the "Create TLK" half of the tool.
 *
 * <p>Usage: {@link #loadInputData(String, FileFormat, boolean)} parses the file, sorts the entries
 * by their original position and builds a Huffman code from the character frequencies of all
 * strings; {@link #saveToTlkFile(String, DoubleConsumer)} then encodes the strings and writes
 * header, string refs, tree and data in the layout described in {@link TlkFile}. An instance can
 * be reused: every load replaces the previous data.
 *
 * <p>Differences from the C# original, which this class otherwise follows: identical strings are
 * stored once and share their bit offset (the game's files do the same; shared tails are not
 * looked for), the Huffman tree is built deterministically (ties are broken by first occurrence),
 * a file without text gives a TLK file without tree instead of an error, files from tool
 * versions before 1.0.3 are rejected instead of only causing a warning, and TXT and CSV files can
 * be read as well as XML.
 */
class HuffmanCompression {

    /** Oldest {@code TLKToolVersion} accepted: earlier versions wrote broken female entries. */
    static final String MIN_TOOL_VERSION = "1.0.3";
    /** Version assumed for an XML file without {@code TLKToolVersion} attribute, as in the C# original. */
    static final String DEFAULT_TOOL_VERSION = "1.0.0.0";
    /**
     * Bit 27 of a string id: set for the strings that are not localizable and therefore never get
     * the debug prefix. The C# original uses the same mask; in {@code BIOGame_RUS.tlk} only the
     * ids {@code 0x8000001} to {@code 0x8000003} (the gender words such as "Male"/"Female") have it.
     */
    static final int NON_LOCALIZABLE_FLAG = 0x0800_0000;
    /** Line break that the C# original's version 1.0.1 started replacing, since the game shows it twice. */
    private static final String CRLF = "\r\n";

    /**
     * {@code TLKToolVersion} of the loaded file ({@link TlkFile} writes {@value TlkFile#TOOL_VERSION}),
     * or {@link #DEFAULT_TOOL_VERSION} before loading and for an XML file without the attribute.
     */
    private String inputFileVersion = DEFAULT_TOOL_VERSION;
    /**
     * Entries read from the file, with CRLF line breaks replaced and the debug prefix added,
     * sorted by position.
     */
    private final List<TlkEntry> inputData = new ArrayList<>();
    /**
     * Number of occurrences of each character, terminators included, over all distinct texts, in
     * order of first occurrence; the weights of the Huffman tree.
     */
    private final Map<Character, Integer> frequencyCount = new LinkedHashMap<>();
    /** Root of the Huffman tree; {@code null} if no entry has text. */
    private HuffmanNode huffmanRoot;
    /**
     * Huffman code of each character: bit {@code i} ({@link BitArray#get(int)}) is the
     * {@code i}-th branch from the root, {@code false} = left, {@code true} = right.
     */
    private final Map<Character, BitArray> huffmanCodes = new HashMap<>();
    /**
     * The codes of {@link #huffmanCodes} packed for {@link BitWriter#append(long, int)}, indexed by
     * character: bit {@code i} of {@code codeBits[c]} is code bit {@code i} of {@code c}. Only
     * filled for codes of at most {@link BitWriter#MAX_PACKED_BITS} bits; {@code null} until a
     * tree is built.
     */
    private long[] codeBits;
    /**
     * Length of the code of each character, indexed by character: {@code 0} for a character
     * without code, {@code -1} for a code too long for {@link #codeBits} (encoded from
     * {@link #huffmanCodes} instead); {@code null} until a tree is built.
     */
    private int[] codeLength;

    /**
     * Creates an empty compressor; call {@link #loadInputData(String, FileFormat, boolean)}
     * before {@link #saveToTlkFile(String, DoubleConsumer)}.
     */
    HuffmanCompression() {
    }

    /**
     * Node of the in-memory Huffman tree used for compression: a leaf for one character, or an
     * inner node with exactly two children.
     */
    private static final class HuffmanNode {
        /** Character of a leaf; unused (left at {@code '\0'}) in inner nodes. */
        final char data;
        /** Leaf: occurrences of {@link #data}; inner node: sum of the children's counts. */
        final int frequencyCount;
        /** Creation order; breaks ties between equal frequencies, so the tree is deterministic. */
        final int sequence;
        /** Left child, reached with a 0 bit; {@code null} in leaves. */
        final HuffmanNode left;
        /** Right child, reached with a 1 bit; {@code null} in leaves. */
        final HuffmanNode right;
        /**
         * Id written to the TLK tree, assigned by {@link HuffmanCompression#treeToBuffer()}: inner
         * nodes get their breadth-first index ({@code 0} is the root), leaves get
         * {@link TlkFile#leafId(char)}.
         */
        int id;

        /**
         * Creates a leaf.
         *
         * @param d        the character
         * @param freq     its number of occurrences
         * @param sequence creation order
         */
        HuffmanNode(char d, int freq, int sequence) {
            this.data = d;
            this.frequencyCount = freq;
            this.sequence = sequence;
            this.left = null;
            this.right = null;
        }

        /**
         * Creates an inner node whose frequency is the sum of its children's.
         *
         * @param left     child for bit 0
         * @param right    child for bit 1
         * @param sequence creation order
         */
        HuffmanNode(HuffmanNode left, HuffmanNode right, int sequence) {
            this.data = '\0';
            this.frequencyCount = left.frequencyCount + right.frequencyCount;
            this.sequence = sequence;
            this.left = left;
            this.right = right;
        }

        /**
         * Tells whether this node is a leaf.
         *
         * @return {@code true} if the node has no children
         */
        boolean isLeaf() {
            return left == null;
        }
    }

    /**
     * Appends bit sequences to a growing byte array, least significant bit first: bit {@code k}
     * of the stream becomes bit {@code k % 8} (value {@code 1 << (k % 8)}) of byte {@code k / 8},
     * the order in which {@link TlkFile} reads the data block with {@link BitArray#getRev(int)}.
     */
    static final class BitWriter {
        /**
         * Longest code {@link #append(long, int)} accepts: shifted by up to 7 bits to its position
         * in the current byte, it must still fit into a {@code long}.
         */
        static final int MAX_PACKED_BITS = Long.SIZE - 8;

        /** Packed bits; grows as needed. */
        private byte[] bytes = new byte[1024];
        /** Number of bits written so far. */
        private int bitCount;

        /** Creates an empty writer. */
        BitWriter() {
        }

        /**
         * Appends the bits of a code, {@code code.get(0)} first.
         *
         * @param code the bits to append; may be empty
         * @throws IllegalStateException if the stream would exceed {@link Integer#MAX_VALUE}
         *                               bits, the largest bit offset a TLK file can hold
         */
        void append(BitArray code) {
            int length = code.length();
            ensureRoom(length);
            for (int i = 0; i < length; i++, bitCount++) {
                if (code.get(i)) {
                    bytes[bitCount >>> 3] |= (byte) (1 << (bitCount & 7));
                }
            }
        }

        /**
         * Appends a code packed into a {@code long}, bit {@code 0} first; does the same as
         * {@link #append(BitArray)} a byte at a time instead of a bit at a time.
         *
         * @param code   the bits, bit {@code i} being code bit {@code i}; bits from {@code length}
         *               up must be zero
         * @param length number of bits, from 0 to {@link #MAX_PACKED_BITS}
         * @throws IllegalStateException if the stream would exceed {@link Integer#MAX_VALUE}
         *                               bits, the largest bit offset a TLK file can hold
         */
        void append(long code, int length) {
            ensureRoom(length);
            int index = bitCount >>> 3;
            int shift = bitCount & 7;
            long shifted = code << shift;
            // the bytes from bitCount on are still zero, so OR-ing the shifted code in is enough
            for (int end = (shift + length + 7) >>> 3, i = 0; i < end; i++) {
                bytes[index + i] |= (byte) (shifted >>> (8 * i));
            }
            bitCount += length;
        }

        /**
         * Grows {@link #bytes} so that {@code length} more bits fit.
         *
         * @param length number of bits about to be appended
         * @throws IllegalStateException if the stream would exceed {@link Integer#MAX_VALUE} bits
         */
        private void ensureRoom(int length) {
            if (bitCount > Integer.MAX_VALUE - length) {
                throw new IllegalStateException("The TLK data block would exceed " + Integer.MAX_VALUE + " bits");
            }
            int neededBytes = (int) (((long) bitCount + length + 7) / 8);
            if (neededBytes > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(neededBytes, 2 * bytes.length));
            }
        }

        /**
         * Returns the number of bits written, which is also the bit offset of the next append.
         *
         * @return the bit count
         */
        int bitCount() {
            return bitCount;
        }

        /**
         * Returns the written bits; the last byte is zero-padded.
         *
         * @return {@code ceil(bitCount() / 8)} bytes
         */
        byte[] toByteArray() {
            return Arrays.copyOf(bytes, (int) (((long) bitCount + 7) / 8));
        }
    }

    /**
     * Loads a file written by {@link TlkFile} and prepares the Huffman coding for it, replacing
     * anything loaded before. If loading fails, the instance is left empty.
     *
     * <p>Reads the entries with the reader of the format ({@link XmlFormat#read(Path)},
     * {@link TxtFormat#read(Path)} or {@link CsvFormat#read(Path)}, which also checks the tool
     * version with {@link #checkToolVersion(String, String, String, Path)}), then treats every
     * format alike: replaces CRLF line breaks in the texts with LF (as the C# original does, since
     * the game shows CRLF as two line breaks), adds the debug prefix if requested, sorts the
     * entries by position, checks that no id occurs more than twice (a TLK file has room for a
     * male and a female variant of a string, nothing more) and builds the Huffman tree and codes
     * ({@link #prepareHuffmanCoding()}).
     *
     * @param fileName     path of the file
     * @param format       input format
     * @param debugVersion if {@code true}, every localizable string (id not negative and
     *                     {@link #NON_LOCALIZABLE_FLAG} clear) is prefixed with
     *                     {@code "(#<id>) "} so that its id is visible in game
     * @throws IOException if the file cannot be read, is malformed (not well-formed XML, not a
     *                     {@code <tlkFile>} document, a TXT or CSV file without its header or with
     *                     a malformed line, an invalid id, position or no-text marker, ...), was
     *                     written by a tool version older than {@value #MIN_TOOL_VERSION}, or has
     *                     an id more than twice; the message names the file and the problem (and
     *                     the line, where there is one)
     */
    public void loadInputData(String fileName, FileFormat format, boolean debugVersion) throws IOException {
        reset();
        try {
            Path path = Paths.get(fileName);
            InputFile input;
            switch (format) {
                case XML:
                    input = XmlFormat.read(path);
                    break;
                case TXT:
                    input = TxtFormat.read(path);
                    break;
                case CSV:
                    input = CsvFormat.read(path);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported input format: " + format);
            }
            inputFileVersion = input.toolVersion;
            for (TlkEntry entry : input.entries) {
                inputData.add(prepareEntry(entry, debugVersion));
            }
            inputData.sort(Comparator.comparingInt(e -> e.position));
            checkIdOccurrences(fileName);
            prepareHuffmanCoding();
        } catch (IOException | RuntimeException e) {
            reset();
            throw e;
        }
    }

    /**
     * Prepares the text of an entry for encoding: CRLF line breaks become LF and, if
     * {@code debugVersion} is set, the id is not negative and the {@link #NON_LOCALIZABLE_FLAG} is
     * clear, the text is prefixed with {@code "(#<id>) "}.
     *
     * @param entry        the entry as read from the file
     * @param debugVersion whether to add the debug prefix
     * @return {@code entry} itself if it has no text, otherwise an entry with the prepared text
     */
    private static TlkEntry prepareEntry(TlkEntry entry, boolean debugVersion) {
        if (entry.data == null) {
            return entry;
        }
        String data = entry.data.replace(CRLF, "\n");
        if (debugVersion && entry.stringId >= 0 && (entry.stringId & NON_LOCALIZABLE_FLAG) == 0) {
            data = "(#" + entry.stringId + ") " + data;
        }
        return new TlkEntry(entry.stringId, entry.position, data);
    }

    /**
     * Checks the tool version declared by an input file; shared by the readers of all formats.
     *
     * @param version    the version, e.g. {@code "1.0.4"}
     * @param formatName name of the format, for the error message
     * @param note       appended to the version in the error message (e.g. why it was assumed);
     *                   may be empty
     * @param file       the file, for the error message
     * @throws IOException if the version is not a dotted list of numbers or is older than
     *                     {@value #MIN_TOOL_VERSION}
     */
    static void checkToolVersion(String version, String formatName, String note, Path file) throws IOException {
        int comparison;
        try {
            comparison = compareVersions(version, MIN_TOOL_VERSION);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + XmlFormat.VERSION_ATTRIBUTE + " \"" + version + "\": " + file, e);
        }
        if (comparison < 0) {
            throw new IOException("The " + formatName + " file was written by TLK tool version " + version + note
                    + "; files from versions before " + MIN_TOOL_VERSION + " are not supported: " + file);
        }
    }

    /** Forgets everything loaded, returning to the state of a new instance. */
    private void reset() {
        inputFileVersion = DEFAULT_TOOL_VERSION;
        inputData.clear();
        frequencyCount.clear();
        huffmanCodes.clear();
        codeBits = null;
        codeLength = null;
        huffmanRoot = null;
    }

    /**
     * Encodes the loaded entries and writes them as a TLK file, replacing any existing file.
     * The data must have been loaded with {@link #loadInputData(String, FileFormat, boolean)};
     * without it, a TLK file without entries is written.
     *
     * <p>Entries are processed in position order. The first entry for a string id goes to the
     * first (male) section, the second one to the second (female) section; each section keeps the
     * position order. Each distinct text is Huffman-coded once, with a {@code '\0'} terminator,
     * and every entry with that text gets the bit offset where it starts; entries without text
     * get the offset {@code -1}. The header holds magic {@code 7040084}, version 3, minimum
     * version 2, both section sizes, the number of tree nodes ({@link #treeToBuffer()}) and the
     * data length in bytes; all integers are little-endian.
     *
     * @param fileName path of the TLK file to write
     * @param progress receives the completed fraction (0 to 1) as the entries are encoded; may be
     *                 {@code null}. It is called on the calling thread
     * @throws IOException if the file cannot be written
     */
    public void saveToTlkFile(String fileName, DoubleConsumer progress) throws IOException {
        ProgressReporter reporter = new ProgressReporter(progress, inputData.size());
        List<TlkFile.TlkStringRef> male = new ArrayList<>();
        List<TlkFile.TlkStringRef> female = new ArrayList<>();
        Set<Integer> maleIds = new HashSet<>();
        Map<String, Integer> offsetByText = new HashMap<>();
        BitWriter data = new BitWriter();

        for (TlkEntry entry : inputData) {
            int offset = -1;
            if (entry.data != null) {
                Integer known = offsetByText.get(entry.data);
                if (known == null) {
                    known = data.bitCount();
                    encode(entry.data, data);
                    offsetByText.put(entry.data, known);
                }
                offset = known;
            }
            TlkFile.TlkStringRef ref = new TlkFile.TlkStringRef(entry.stringId, offset);
            (maleIds.add(entry.stringId) ? male : female).add(ref);
            reporter.step();
        }

        int[] tree = treeToBuffer();
        byte[] bytes = data.toByteArray();
        TlkFile.TlkHeader header = new TlkFile.TlkHeader(male.size(), female.size(), tree.length / 2, bytes.length);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(Paths.get(fileName)))) {
            header.write(out);
            for (TlkFile.TlkStringRef ref : male) {
                ref.write(out);
            }
            for (TlkFile.TlkStringRef ref : female) {
                ref.write(out);
            }
            for (int child : tree) {
                TlkFile.writeInt32(out, child);
            }
            out.write(bytes);
        }
        reporter.finish();
    }

    /**
     * Appends the Huffman codes of a text and of its {@code '\0'} terminator.
     *
     * @param text the text, without terminator
     * @param out  the data block being built
     */
    private void encode(String text, BitWriter out) {
        for (int i = 0; i < text.length(); i++) {
            encode(text.charAt(i), out);
        }
        encode('\0', out);
    }

    /**
     * Appends the Huffman code of one character, packed if it is short enough.
     *
     * @param c   a character with a code
     * @param out the data block being built
     */
    private void encode(char c, BitWriter out) {
        int length = codeLength[c];
        if (length >= 0) {
            out.append(codeBits[c], length);
        } else {
            out.append(huffmanCodes.get(c));
        }
    }

    /**
     * Compares two dotted numeric version strings component by component; missing components
     * count as 0, so {@code "1.0.3"} equals {@code "1.0.3.0"}.
     *
     * @param ver1 left-hand version, e.g. {@code "1.0.4"}
     * @param ver2 right-hand version
     * @return a negative number, zero or a positive number as {@code ver1} is older than, equal to
     *         or newer than {@code ver2}
     * @throws NumberFormatException if a component is not an integer
     */
    static int compareVersions(String ver1, String ver2) {
        String[] parts1 = ver1.split("\\.");
        String[] parts2 = ver2.split("\\.");
        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (v1 != v2) {
                return Integer.compare(v1, v2);
            }
        }
        return 0;
    }

    /**
     * Checks that no string id occurs more than twice.
     *
     * @param fileName the file, for the error message
     * @throws IOException naming the first id found three times and its positions
     */
    private void checkIdOccurrences(String fileName) throws IOException {
        Map<Integer, Integer> occurrences = new HashMap<>();
        for (TlkEntry entry : inputData) {
            if (occurrences.merge(entry.stringId, 1, Integer::sum) > 2) {
                List<Integer> positions = new ArrayList<>();
                for (TlkEntry e : inputData) {
                    if (e.stringId == entry.stringId) {
                        positions.add(e.position);
                    }
                }
                throw new IOException("String id " + entry.stringId + " occurs " + positions.size()
                        + " times (positions " + positions + "), but a TLK file can only hold a male and a "
                        + "female variant of a string: " + fileName);
            }
        }
    }

    /**
     * Creates the Huffman tree and the code of every character from the loaded texts.
     *
     * <p>Counts every character of every distinct text, plus one {@code '\0'} terminator per
     * text, into {@link #frequencyCount} (entries without text are skipped), then builds the tree
     * by repeatedly merging the two least frequent nodes (the less frequent one becomes the left
     * child; ties go to the node created first) and derives the codes
     * ({@link #buildCodes(HuffmanNode, boolean[], int)}). With a single distinct character, the
     * root is that leaf and its code is a single 0 bit; without text there is no tree.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Huffman_coding">Huffman coding (Wikipedia)</a>
     */
    private void prepareHuffmanCoding() {
        Set<String> texts = new LinkedHashSet<>();
        for (TlkEntry entry : inputData) {
            if (entry.data != null) {
                texts.add(entry.data);
            }
        }
        // count into an array (no boxing per character), remembering the order of first occurrence
        int[] counts = new int[Character.MAX_VALUE + 1];
        StringBuilder order = new StringBuilder();
        for (String text : texts) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (counts[c]++ == 0) {
                    order.append(c);
                }
            }
            if (counts['\0']++ == 0) {
                order.append('\0');
            }
        }
        for (int i = 0; i < order.length(); i++) {
            char c = order.charAt(i);
            frequencyCount.put(c, counts[c]);
        }
        if (frequencyCount.isEmpty()) {
            return;
        }

        PriorityQueue<HuffmanNode> queue = new PriorityQueue<>(
                Comparator.<HuffmanNode>comparingInt(n -> n.frequencyCount).thenComparingInt(n -> n.sequence));
        int sequence = 0;
        for (Map.Entry<Character, Integer> e : frequencyCount.entrySet()) {
            queue.add(new HuffmanNode(e.getKey(), e.getValue(), sequence++));
        }
        while (queue.size() > 1) {
            HuffmanNode left = queue.poll();
            HuffmanNode right = queue.poll();
            queue.add(new HuffmanNode(left, right, sequence++));
        }
        huffmanRoot = queue.poll();

        codeBits = new long[Character.MAX_VALUE + 1];
        codeLength = new int[Character.MAX_VALUE + 1];
        if (huffmanRoot.isLeaf()) {
            putCode(huffmanRoot.data, new boolean[]{false}, 1);
        } else {
            buildCodes(huffmanRoot, new boolean[frequencyCount.size()], 0);
        }
    }

    /**
     * Stores the code of a character in {@link #huffmanCodes} and, if it is short enough, packed
     * in {@link #codeBits} and {@link #codeLength}.
     *
     * @param c      the character
     * @param path   the code bits in its first {@code length} elements
     * @param length the code length
     */
    private void putCode(char c, boolean[] path, int length) {
        huffmanCodes.put(c, new BitArray(Arrays.copyOf(path, length)));
        if (length > BitWriter.MAX_PACKED_BITS) {
            codeLength[c] = -1;
            return;
        }
        long bits = 0;
        for (int i = 0; i < length; i++) {
            if (path[i]) {
                bits |= 1L << i;
            }
        }
        codeBits[c] = bits;
        codeLength[c] = length;
    }

    /**
     * Recursively traverses the Huffman tree and stores the code of every leaf in
     * {@link #huffmanCodes}: the path from the root to the leaf, with {@code false} for each step
     * left and {@code true} for each step right.
     *
     * @param node  subtree to process
     * @param path  branches taken from the root to {@code node} in its first {@code depth}
     *              elements; long enough for the deepest leaf (the number of characters)
     * @param depth depth of {@code node}
     */
    private void buildCodes(HuffmanNode node, boolean[] path, int depth) {
        if (node.isLeaf()) {
            putCode(node.data, path, depth);
            return;
        }
        path[depth] = false;
        buildCodes(node.left, path, depth + 1);
        path[depth] = true;
        buildCodes(node.right, path, depth + 1);
    }

    /**
     * Converts the Huffman tree to its binary representation used by the TLK format of Mass
     * Effect 2: one {@code (left, right)} pair of ints per inner node, in node-id order.
     *
     * <p>Ids are assigned breadth-first from the root, visiting the right child before the left
     * one (as the C# original does): inner nodes get consecutive indices from 0, so the root is
     * node 0 as the reader expects; leaves get {@link TlkFile#leafId(char)}. A root that is itself
     * a leaf (a single distinct character) becomes one node with that leaf on both sides.
     *
     * @return {@code 2 * innerNodeCount} ints: left and right id of node 0, then of node 1, and
     *         so on; empty if there is no tree
     */
    private int[] treeToBuffer() {
        if (huffmanRoot == null) {
            return new int[0];
        }
        if (huffmanRoot.isLeaf()) {
            int leaf = TlkFile.leafId(huffmanRoot.data);
            return new int[]{leaf, leaf};
        }
        List<HuffmanNode> innerNodes = new ArrayList<>();
        Deque<HuffmanNode> queue = new ArrayDeque<>();
        queue.add(huffmanRoot);
        while (!queue.isEmpty()) {
            HuffmanNode node = queue.remove();
            if (node.isLeaf()) {
                node.id = TlkFile.leafId(node.data);
            } else {
                node.id = innerNodes.size();
                innerNodes.add(node);
                queue.add(node.right);
                queue.add(node.left);
            }
        }
        int[] buffer = new int[2 * innerNodes.size()];
        for (int i = 0; i < innerNodes.size(); i++) {
            buffer[2 * i] = innerNodes.get(i).left.id;
            buffer[2 * i + 1] = innerNodes.get(i).right.id;
        }
        return buffer;
    }
}
