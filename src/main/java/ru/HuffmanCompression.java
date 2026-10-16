package ru;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Builds a Mass Effect 2 {@code .tlk} file from the XML written by {@link TlkFile}: the
 * "Create TLK" half of the tool.
 *
 * <p>Usage: {@link #loadInputData(String, FileFormat, boolean)} parses the XML, sorts the entries
 * by their original position and builds a Huffman code from the character frequencies of all
 * strings; {@link #saveToTlkFile(String, boolean)} then encodes every string and writes header,
 * string refs, tree and data in the layout described in {@link TlkFile}. An instance can be
 * reused: every load replaces the previous data.
 *
 * <p>Note: the writer does not yet produce a valid TLK file; see the notes on
 * {@link #saveToTlkFile(String, boolean)}.
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
     * {@code TLKToolVersion} attribute of the loaded XML ({@link TlkFile} writes 1.0.4), or
     * {@link #DEFAULT_TOOL_VERSION} before loading and if the attribute is missing.
     */
    private String inputFileVersion = DEFAULT_TOOL_VERSION;
    /** Entries read from the XML, sorted by position after loading. */
    private final List<TlkEntry> inputData = new ArrayList<>();
    /**
     * Number of occurrences of each character, terminators included, over all entries with text,
     * in order of first occurrence; the weights of the Huffman tree.
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
     * Creates an empty compressor; call {@link #loadInputData(String, FileFormat, boolean)}
     * before {@link #saveToTlkFile(String, boolean)}.
     */
    HuffmanCompression() {
    }

    /** One {@code <string>} element of the input XML. */
    private static final class TlkEntry {
        /** String id from {@code <id>}. */
        final int stringId;
        /** Index of the entry in the original string-ref table, from {@code <position>}. */
        final int position;
        /**
         * Text to encode, without terminator but with the debug prefix if requested; {@code null}
         * if the entry has no text.
         */
        final String data;

        /**
         * Creates an entry.
         *
         * @param stringId string id
         * @param position index in the original string-ref table
         * @param data     text, or {@code null} for an entry without text
         */
        TlkEntry(int stringId, int position, String data) {
            this.stringId = stringId;
            this.position = position;
            this.data = data;
        }
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
     * Loads an XML file written by {@link TlkFile} and prepares the Huffman coding for it,
     * replacing anything loaded before. If loading fails, the instance is left empty.
     *
     * <p>Reads every {@code <string>} element (see {@link #readXml(Path, boolean)}), sorts the
     * entries by position and builds the Huffman tree and codes ({@link #prepareHuffmanCoding()}).
     *
     * @param fileName     path of the XML file
     * @param ff           input format; currently ignored, the file is always parsed as XML
     * @param debugVersion if {@code true}, every localizable string is prefixed with
     *                     {@code "(#<id>) "} so that its id is visible in game
     * @throws IOException if the file cannot be read, is not well-formed XML, is not a
     *                     {@code <tlkFile>} document, was written by a tool version older than
     *                     {@value #MIN_TOOL_VERSION} (a missing version counts as
     *                     {@value #DEFAULT_TOOL_VERSION}) or has an invalid {@code <id>},
     *                     {@code <position>} or no-text {@code <data>}; the message names the
     *                     file and the problem
     */
    public void loadInputData(String fileName, FileFormat ff, boolean debugVersion) throws IOException {
        reset();
        try {
            readXml(Paths.get(fileName), debugVersion);
            inputData.sort(Comparator.comparingInt(e -> e.position));
            prepareHuffmanCoding();
        } catch (IOException | RuntimeException e) {
            reset();
            throw e;
        }
    }

    /** Forgets everything loaded, returning to the state of a new instance. */
    private void reset() {
        inputFileVersion = DEFAULT_TOOL_VERSION;
        inputData.clear();
        frequencyCount.clear();
        huffmanCodes.clear();
        huffmanRoot = null;
    }

    /**
     * Encodes the loaded entries and writes them as a TLK file, replacing any existing file.
     * The data must have been loaded with {@link #loadInputData(String, FileFormat, boolean)}.
     *
     * <p>Entries are processed in position order. The first entry for a string id goes to the
     * first (male) section, a later one with the same id to the second (female) section. For an
     * entry with text, the Huffman codes of its characters and of a {@code '\0'} terminator are
     * appended to the bit stream and the entry stores the bit offset where they start (every
     * string is encoded separately; identical strings and shared tails are not merged as in the
     * game's files). Entries without text get the bit offset {@code -1} and nothing is encoded.
     * The header holds magic {@code 7040084}, version 3, minimum version
     * 2, both section sizes, the number of inner tree nodes and the data length in bytes, followed
     * by the sections, the tree ({@link #treeToBuffer()}) and the packed bits
     * ({@link #bitArrayListToByteArray(List, int)}).
     *
     * <p>Note: integers are written with {@link OutputStream#write(int)}, which writes only the
     * low byte of each value instead of four little-endian bytes, and the two sections are kept in
     * {@link HashMap}s, so entries come out in hash order rather than position order. The output
     * stream is not closed if writing fails.
     *
     * @param fileName path of the TLK file to write
     * @param isPC     meant to choose little-endian (PC) or big-endian (Xbox 360) output, as in
     *                 the C# original; currently ignored
     * @throws IOException if the old file cannot be deleted or the new one cannot be written
     */
    public void saveToTlkFile(String fileName, boolean isPC) throws IOException {
        Files.deleteIfExists(Paths.get(fileName));

        /* converts Huffmann Tree to binary form */
        int[] treeBuffer = treeToBuffer();

        /* preparing data and entries for writing to file
         * entries list consists of pairs <String ID, Offset> */
        List<BitArray> binaryData = new LinkedList<>();
        Map<Integer, Integer> entries1 = new HashMap<>();
        Map<Integer, Integer> entries2 = new HashMap<>();
        int offset = 0;

        for (TlkEntry entry : inputData) {
            if (entry.data == null) {
                if (!entries1.containsKey(entry.stringId)) {
                    entries1.put(entry.stringId, -1);
                } else {
                    entries2.put(entry.stringId, -1);
                }
                continue;
            }

            if (!entries1.containsKey(entry.stringId)) {
                entries1.put(entry.stringId, offset);
            } else {
                entries2.put(entry.stringId, offset);
            }

            /* for every character in a string and its terminator, put its binary code into data array */
            for (char c : (entry.data + '\0').toCharArray()) {
                binaryData.add(huffmanCodes.get(c));
                offset += huffmanCodes.get(c).length();
            }
        }

        /* preparing TLK Header */
        int magic = 7040084;
        int ver = 3;
        int min_ver = 2;
        int entry1Count = entries1.size();
        int entry2Count = entries2.size();
        int treeNodeCount = treeBuffer.length / 2;
        int dataLength = offset / 8;
        if (offset % 8 > 0) {
            ++dataLength;
        }

        /* using LittleEndian for PC architecture and BigEndian for Xbox360 */
//        MiscUtil.Conversion.EndianBitConverter bitConverter;
//        if (isPC)
//            bitConverter = new MiscUtil.Conversion.LittleEndianBitConverter();
//        else
//            bitConverter = new MiscUtil.Conversion.BigEndianBitConverter();

        OutputStream bw = new FileOutputStream(fileName);

//        var bw = new MiscUtil.IO.EndianBinaryWriter(bitConverter, File.OpenWrite(fileName));

        /* writing TLK Header */
        bw.write(magic);
        bw.write(ver);
        bw.write(min_ver);
        bw.write(entry1Count);
        bw.write(entry2Count);
        bw.write(treeNodeCount);
        bw.write(dataLength);

        /* writing entries */
        for (Map.Entry<Integer, Integer> entry : entries1.entrySet()) {
            bw.write(entry.getKey());
            bw.write(entry.getValue());
        }
        for (Map.Entry<Integer, Integer> entry : entries2.entrySet()) {
            bw.write(entry.getKey());
            bw.write(entry.getValue());
        }

        /* writing HuffmanTree */
        for (int element : treeBuffer) {
            bw.write(element);
        }
        /* writing data */
        byte[] data = bitArrayListToByteArray(binaryData, offset);
        bw.write(data);
        bw.close();
    }

    /**
     * Parses the XML file and appends its entries to {@link #inputData}, in document order.
     *
     * <p>The root element must be {@code <tlkFile>}; its {@code TLKToolVersion} attribute is
     * stored in {@link #inputFileVersion} (missing: {@link #DEFAULT_TOOL_VERSION}) and must be at
     * least {@value #MIN_TOOL_VERSION}. Every {@code <string>} element below it becomes an entry
     * (see {@link #readEntry(XMLStreamReader, boolean, Path)}). DTDs and external entities are
     * not processed.
     *
     * @param file         the XML file
     * @param debugVersion whether to add the {@code "(#<id>) "} prefix
     * @throws IOException if the file cannot be read or has invalid content
     */
    private void readXml(Path file, boolean debugVersion) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                readRoot(reader, file);
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && TlkFile.XML_STRING.equals(reader.getLocalName())) {
                        inputData.add(readEntry(reader, debugVersion, file));
                    }
                }
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
     * @throws IOException        if the root is not {@code <tlkFile>} or the version is invalid
     *                            or too old
     * @throws XMLStreamException if the XML is malformed
     */
    private void readRoot(XMLStreamReader reader, Path file) throws IOException, XMLStreamException {
        while (reader.next() != XMLStreamConstants.START_ELEMENT) {
            // skip the prolog: declaration, comments, processing instructions, whitespace
        }
        if (!TlkFile.XML_ROOT.equals(reader.getLocalName())) {
            throw new IOException("Not a TLK XML file (root element <" + reader.getLocalName() + "> instead of <"
                    + TlkFile.XML_ROOT + ">): " + file);
        }
        String version = reader.getAttributeValue(null, TlkFile.XML_VERSION_ATTRIBUTE);
        inputFileVersion = version == null ? DEFAULT_TOOL_VERSION : version;
        int comparison;
        try {
            comparison = compareVersions(inputFileVersion, MIN_TOOL_VERSION);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + TlkFile.XML_VERSION_ATTRIBUTE + " \"" + inputFileVersion + "\": " + file, e);
        }
        if (comparison < 0) {
            throw new IOException("The XML file was written by TLK tool version " + inputFileVersion
                    + (version == null ? " (it has no " + TlkFile.XML_VERSION_ATTRIBUTE + " attribute)" : "")
                    + "; files from versions before " + MIN_TOOL_VERSION + " are not supported: " + file);
        }
    }

    /**
     * Reads one {@code <string>} element.
     *
     * <p>Reads the {@code <id>}, {@code <position>} and {@code <data>} children (missing ones
     * default to 0, 0 and empty text; other children are ignored). An entry has text exactly if
     * its id is not negative (see {@link TlkFile}). Text has its CRLF line breaks replaced with LF;
     * if {@code debugVersion} is set and the {@link #NON_LOCALIZABLE_FLAG} of the id is clear, it
     * is also prefixed with {@code "(#<id>) "}. An entry without text must have a negative number
     * (normally {@code -1}) as its {@code <data>}.
     *
     * @param reader       reader at the {@code <string>} start tag; left at its end tag
     * @param debugVersion whether to add the debug prefix
     * @param file         the file, for error messages
     * @return the entry
     * @throws IOException        if a value is invalid
     * @throws XMLStreamException if the XML is malformed
     */
    private TlkEntry readEntry(XMLStreamReader reader, boolean debugVersion, Path file)
            throws IOException, XMLStreamException {
        int line = reader.getLocation().getLineNumber();
        int id = 0;
        int position = 0;
        String data = "";
        while (reader.next() != XMLStreamConstants.END_ELEMENT) {
            if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String name = reader.getLocalName();
            if (TlkFile.XML_ID.equals(name)) {
                id = parseInt(readText(reader), name, line, file);
            } else if (TlkFile.XML_POSITION.equals(name)) {
                position = parseInt(readText(reader), name, line, file);
            } else if (TlkFile.XML_DATA.equals(name)) {
                data = readText(reader);
            } else {
                readText(reader);
            }
        }

        if (id < 0) {
            if (parseInt(data, TlkFile.XML_DATA, line, file) >= 0) {
                throw new IOException("The <string> at line " + line + " (id " + id + ") has no text, so its <"
                        + TlkFile.XML_DATA + "> must be " + TlkFile.XML_NO_TEXT + ", not \"" + data + "\": " + file);
            }
            return new TlkEntry(id, position, null);
        }
        data = data.replace(CRLF, "\n");
        if (debugVersion && id >= 0 && (id & NON_LOCALIZABLE_FLAG) == 0) {
            data = "(#" + id + ") " + data;
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
                case XMLStreamConstants.START_ELEMENT:
                    depth++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    depth--;
                    break;
                case XMLStreamConstants.CHARACTERS:
                case XMLStreamConstants.CDATA:
                case XMLStreamConstants.SPACE:
                    text.append(reader.getText());
                    break;
                default:
                    break;
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
     * Creates the Huffman tree and the code of every character from the loaded texts.
     *
     * <p>Counts every character of every text, plus one {@code '\0'} terminator per text, into
     * {@link #frequencyCount} (entries without text are skipped), then builds the tree by
     * repeatedly merging the two least frequent nodes (the less frequent one becomes the left
     * child; ties go to the node created first) and derives the codes
     * ({@link #buildCodes(HuffmanNode, boolean[], int)}). With a single distinct character, the
     * root is that leaf and its code is a single 0 bit; without text there is no tree.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Huffman_coding">Huffman coding (Wikipedia)</a>
     */
    private void prepareHuffmanCoding() {
        for (TlkEntry entry : inputData) {
            if (entry.data == null) {
                continue;
            }
            for (int i = 0; i < entry.data.length(); i++) {
                frequencyCount.merge(entry.data.charAt(i), 1, Integer::sum);
            }
            frequencyCount.merge('\0', 1, Integer::sum);
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

        if (huffmanRoot.isLeaf()) {
            huffmanCodes.put(huffmanRoot.data, new BitArray(new boolean[]{false}));
        } else {
            buildCodes(huffmanRoot, new boolean[frequencyCount.size()], 0);
        }
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
            huffmanCodes.put(node.data, new BitArray(Arrays.copyOf(path, depth)));
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

    /**
     * Converts a list of {@link BitArray}s to an array of bytes ready to be written to a file.
     *
     * <p>The bit sequences are concatenated and packed least significant bit first: bit
     * {@code k} of the stream becomes bit {@code k % 8} (value {@code 1 << (k % 8)}) of byte
     * {@code k / 8}, the order {@link TlkFile} reads with {@link BitArray#getRev(int)}. The last
     * byte is zero-padded.
     *
     * @param bitsList  bit sequences in output order (Huffman codes, read with
     *                  {@link BitArray#get(int)})
     * @param bitsCount total number of bits in {@code bitsList}; determines the array size
     * @return {@code ceil(bitsCount / 8)} bytes
     */
    private static byte[] bitArrayListToByteArray(List<BitArray> bitsList, int bitsCount) {
        final int BITSPERBYTE = 8;

        int bytesize = bitsCount / BITSPERBYTE;
        if (bitsCount % BITSPERBYTE > 0)
            bytesize++;

        byte[] bytes = new byte[bytesize];
        int bytepos = 0;
        int bitsRead = 0;
        byte value = 0;
        byte significance = 1;

        for (BitArray bits : bitsList) {
            int bitpos = 0;

            while (bitpos < bits.length()) {
                if (bits.get(bitpos)) {
                    value += significance;
                }
                ++bitpos;
                ++bitsRead;
                if (bitsRead % BITSPERBYTE == 0) {
                    bytes[bytepos] = value;
                    ++bytepos;
                    value = 0;
                    significance = 1;
                    bitsRead = 0;
                }
                else {
                    significance <<= 1;
                }
            }
        }
        if (bitsRead % BITSPERBYTE != 0) {
            bytes[bytepos] = value;
        }
        return bytes;
    }
}
