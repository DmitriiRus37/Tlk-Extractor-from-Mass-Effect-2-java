package ru;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Builds a Mass Effect 2 {@code .tlk} file from the XML written by {@link TlkFile}: the
 * "Create TLK" half of the tool.
 *
 * <p>Usage: {@link #loadInputData(String, FileFormat, boolean)} parses the XML, sorts the entries
 * by their original position and builds a Huffman code from the character frequencies of all
 * strings; {@link #saveToTlkFile(String, boolean)} then encodes every string and writes header,
 * string refs, tree and data in the layout described in {@link TlkFile}. Use one instance per
 * conversion.
 *
 * <p>Note: the writer does not yet produce a valid TLK file; see the notes on
 * {@link #saveToTlkFile(String, boolean)}.
 */
class HuffmanCompression {
    /**
     * {@code TLKToolVersion} attribute of the loaded XML ({@link TlkFile} writes 1.0.4); files
     * older than 1.0.3 are rejected. The initial value is overwritten by every load.
     */
    private String inputFileVersion = "1.0.0.0";
    /**
     * Entries read from the XML, sorted by position after loading. For a non-negative id,
     * {@code data} is the text with a {@code '\0'} terminator appended (and the debug prefix, if
     * enabled); for a negative id it is the bit offset to store, as decimal text (normally
     * {@code "-1"}).
     */
    private final List<TlkEntry> inputData = new LinkedList<>();
    /**
     * Number of occurrences of each character, terminators included, over all entries with a
     * non-negative id; the weights of the Huffman tree.
     */
    private final Map<Character, Integer> frequencyCount = new HashMap<>();
    /**
     * Work list for building the tree: starts with one leaf per character and ends up holding
     * only the root, at index 0.
     */
    private final List<HuffmanNode> huffmanTree = new LinkedList<>();
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

    /**
     * One {@code <string>} element of the input XML. Ordered by {@link #position}, so sorting
     * restores the order of the original TLK string-ref table.
     *
     * <p>Implements the raw {@link Comparable} type, so comparing with anything other than a
     * {@code TlkEntry} throws {@link ClassCastException}.
     */
    private static class TlkEntry implements Comparable {
        /** String id from {@code <id>}; negative ids mark entries without text. */
        public int stringID;
        /** Index of the entry in the original string-ref table, from {@code <position>}. */
        public int position;
        /** Prepared text or bit offset; see {@link HuffmanCompression#inputData}. */
        public String data;

        /**
         * Creates an entry.
         *
         * @param stringID string id
         * @param position index in the original string-ref table
         * @param data     prepared text or, for a negative id, the bit offset as text
         */
        public TlkEntry(int stringID, int position, String data) {
            this.stringID = stringID;
            this.position = position;
            this.data = data;
        }

        /**
         * Compares by {@link #position}.
         *
         * @param o the other {@code TlkEntry}
         * @return a negative number, zero or a positive number as this entry's position is less
         *         than, equal to or greater than that of {@code o}
         * @throws ClassCastException if {@code o} is not a {@code TlkEntry}
         */
        @Override
        public int compareTo(Object o) {
            return Integer.compare(this.position, ((TlkEntry) o).position);
        }

    }

    /**
     * Node of the in-memory Huffman tree used for compression: a leaf for one character, or an
     * inner node with exactly two children.
     */
    private static class HuffmanNode {
        /** Character of a leaf; unused (left at {@code '\0'}) in inner nodes. */
        public char data;
        /** Leaf: occurrences of {@link #data}; inner node: sum of the children's counts. */
        public int frequencyCount;
        /** Left child, reached with a 0 bit; {@code null} in leaves. */
        public HuffmanNode left;
        /** Right child, reached with a 1 bit; {@code null} in leaves. */
        public HuffmanNode right;
        /**
         * Id written to the TLK tree, assigned by
         * {@link HuffmanCompression#ConvertHuffmanTreeToBuffer()}: inner nodes get their
         * breadth-first index ({@code 0} is the root), leaves get {@code -1 - data}.
         */
        public int id;

        /**
         * Creates a leaf.
         *
         * @param d    the character
         * @param freq its number of occurrences
         */
        public HuffmanNode(char d, int freq) {
            data = d;
            frequencyCount = freq;
        }

        /**
         * Creates an inner node whose frequency is the sum of its children's.
         *
         * @param left  child for bit 0
         * @param right child for bit 1
         */
        public HuffmanNode(HuffmanNode left, HuffmanNode right) {
            frequencyCount = left.frequencyCount + right.frequencyCount;
            this.left = left;
            this.right = right;
        }
    }

    /**
     * Loads an XML file written by {@link TlkFile} and prepares the Huffman coding for it.
     *
     * <p>Clears the previously loaded entries, parses the file (see
     * {@link #LoadXmlInputData(String, boolean)}), sorts the entries by position and builds the
     * Huffman tree and codes ({@link #PrepareHuffmanCoding()}).
     *
     * <p>Note: the tree work list and code table are not cleared, so loading a second file into
     * the same instance mixes the previous tree into the new one.
     *
     * @param fileName     path of the XML file
     * @param ff           input format; currently ignored, the file is always parsed as XML
     * @param debugVersion if {@code true}, every localizable string is prefixed with
     *                     {@code "(#<id>) "} so that its id is visible in game
     * @throws IOException                  if the file cannot be read
     * @throws ParserConfigurationException if no XML parser can be created
     * @throws SAXException                 if the file is not well-formed XML
     * @throws RuntimeException             (without a message) if the XML was written by a tool
     *                                      version older than 1.0.3
     */
    public void loadInputData(String fileName, FileFormat ff, boolean debugVersion) throws IOException, ParserConfigurationException, SAXException {
        inputData.clear();
        LoadXmlInputData(fileName, debugVersion);
        Collections.sort(inputData);
        PrepareHuffmanCoding();
    }

    /**
     * Encodes the loaded entries and writes them as a TLK file, replacing any existing file.
     * The data must have been loaded with {@link #loadInputData(String, FileFormat, boolean)}.
     *
     * <p>Entries are processed in position order. The first entry for a string id goes to the
     * first (male) section, a later one with the same id to the second (female) section. For a
     * non-negative id, the Huffman codes of the string's characters are appended to the bit
     * stream and the entry stores the bit offset where they start (every string is encoded
     * separately; identical strings and shared tails are not merged as in the game's files). For
     * a negative id, the entry's text is parsed as the bit offset to store (normally {@code -1})
     * and nothing is encoded. The header holds magic {@code 7040084}, version 3, minimum version
     * 2, both section sizes, the number of inner tree nodes and the data length in bytes, followed
     * by the sections, the tree ({@link #ConvertHuffmanTreeToBuffer()}) and the packed bits
     * ({@link #BitArrayListToByteArray(List, int)}).
     *
     * <p>Note: integers are written with {@link OutputStream#write(int)}, which writes only the
     * low byte of each value instead of four little-endian bytes, and the two sections are kept in
     * {@link HashMap}s, so entries come out in hash order rather than position order. The output
     * stream is not closed if writing fails.
     *
     * @param fileName path of the TLK file to write
     * @param isPC     meant to choose little-endian (PC) or big-endian (Xbox 360) output, as in
     *                 the C# original; currently ignored
     * @throws IOException           if the old file cannot be deleted or the new one cannot be
     *                               written
     * @throws NumberFormatException if an entry with a negative id has non-numeric text
     */
    public void saveToTlkFile(String fileName, boolean isPC) throws IOException {
        Files.deleteIfExists(Paths.get(fileName));

        /* converts Huffmann Tree to binary form */
        List<Integer> treeBuffer = ConvertHuffmanTreeToBuffer();

        /* preparing data and entries for writing to file
         * entries list consists of pairs <String ID, Offset> */
        List<BitArray> binaryData = new LinkedList<>();
        Map<Integer, Integer> entries1 = new HashMap<>();
        Map<Integer, Integer> entries2 = new HashMap<>();
        int offset = 0;

        for (TlkEntry entry : inputData) {
            if (entry.stringID < 0) {
                if (!entries1.containsKey(entry.stringID)) {
                    entries1.put(entry.stringID, Integer.parseInt(entry.data));
                } else {
                    entries2.put(entry.stringID, Integer.parseInt(entry.data));
                }
                continue;
            }

            if (!entries1.containsKey(entry.stringID)) {
                entries1.put(entry.stringID, offset);
            } else {
                entries2.put(entry.stringID, offset);
            }

            /* for every character in a string, put it's binary code into data array */
            for (char c : entry.data.toCharArray()) {
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
        int treeNodeCount = treeBuffer.size() / 2;
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
        byte[] data = BitArrayListToByteArray(binaryData, offset);
        bw.write(data);
        bw.close();
    }

    /**
     * Parses the XML file and appends its entries to {@link #inputData}.
     *
     * <p>Stores the root's {@code TLKToolVersion} attribute in {@link #inputFileVersion}, then
     * reads the {@code <id>}, {@code <position>} and {@code <data>} children of every
     * {@code <string>} element (missing ones default to 0, 0 and empty text) and replaces CRLF
     * line breaks in the text with LF. Text of an entry with a non-negative id gets a
     * {@code '\0'} terminator appended; if {@code debugVersion} is set and bit 27
     * ({@code 0x08000000}) of the id is clear (the code's notion of "localizable"), it is also
     * prefixed with {@code "(#<id>) "}. Entries with a negative id are kept as they are.
     *
     * <p>Note: a missing {@code TLKToolVersion} attribute causes a {@link NullPointerException}.
     *
     * @param fileName     path of the XML file
     * @param debugVersion whether to add the {@code "(#<id>) "} prefix
     * @throws IOException                  if the file cannot be read
     * @throws SAXException                 if the file is not well-formed XML
     * @throws ParserConfigurationException if no XML parser can be created
     * @throws NumberFormatException        if an {@code <id>} or {@code <position>} is not an
     *                                      integer
     * @throws RuntimeException             (without a message) if {@code TLKToolVersion} is
     *                                      older than 1.0.3
     */
    private void LoadXmlInputData(String fileName, boolean debugVersion) throws IOException, SAXException, ParserConfigurationException {
        // Получение фабрики, чтобы после получить билдер документов.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Получили из фабрики билдер, который парсит XML, создает структуру Document в виде иерархического дерева.
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Запарсили XML, создав структуру Document. Теперь у нас есть доступ ко всем элементам, каким нам нужно.
        Document document = builder.parse(new File(fileName));
        // Получение списка всех элементов tlkFile внутри корневого элемента (getDocumentElement возвращает ROOT элемент XML файла).
        NodeList tlkFileElements = document.getDocumentElement().getElementsByTagName("tlkFile");
        /* read and store TLK Tool version, which was used to create the XML file */
        String toolVersion = String.valueOf(document.getDocumentElement().getAttributes().getNamedItem("TLKToolVersion").getNodeValue());
        if (toolVersion != null) {
            inputFileVersion = toolVersion;
        }

        // Получение списка всех элементов string внутри корневого элемента.
        NodeList stringElements = document.getDocumentElement().getElementsByTagName("string");
        for (int i=0; i< stringElements.getLength(); i++) {
            int id = 0, position = 0;
            String data = "";
            NodeList childNodes = stringElements.item(i).getChildNodes();

            for (int j=0; j<childNodes.getLength(); j++) {
                if (childNodes.item(j).getNodeName().equals("id")) {
                    id = Integer.parseInt(childNodes.item(j).getTextContent());
                } else if (childNodes.item(j).getNodeName().equals("position")) {
                    position = Integer.parseInt(childNodes.item(j).getTextContent());
                } else if (childNodes.item(j).getNodeName().equals("data")) {
                    data = childNodes.item(j).getTextContent();
                }
            }
            data = data.replace("\r\n", "\n");
            /* every String should be NULL-terminated */
            if (id >= 0) {
                data += '\0';
            }
            /* only add debug info if we are in debug mode and StringID is positive AND it's localizable */
            inputData.add(id >= 0 && debugVersion && (id & 0x8000000) != 0x8000000 ?
                    new TlkEntry(id, position, "(#" + id + ") " + data) :
                    new TlkEntry(id, position, data)
            );
        }

        /* code for XML files created BEFORE v. 1.0.3 */
        String lastEntryFixVersion = "1.0.3";

        /* check if someone isn't loading the bugged version < 1.0.3 */
        if (compareVersionStrings(inputFileVersion, lastEntryFixVersion, "<")) {
            throw new RuntimeException();
        }
    }

    /**
     * Compares two dotted numeric version strings component by component; missing components
     * count as 0, so {@code "1.0.3"} equals {@code "1.0.3.0"}.
     *
     * @param ver1 left-hand version, e.g. {@code "1.0.4"}
     * @param ver2 right-hand version
     * @param sign relation to test: {@code "<"}, {@code ">"} or {@code "="}
     * @return whether {@code ver1 sign ver2} holds
     * @throws NumberFormatException if a component is not an integer
     * @throws RuntimeException      if {@code sign} is not one of the three operators
     */
    private static boolean compareVersionStrings(String ver1, String ver2, String sign) {
        String[] arr1 = ver1.split("\\.");
        String[] arr2 = ver2.split("\\.");
        int len = Math.max(arr1.length, arr2.length);
        int[] arr1New = new int[len];
        int[] arr2New = new int[len];

        for (int i=0; i<len; i++) {
            arr1New[i] = arr1.length > i ? Integer.parseInt(arr1[i]) : 0;
            arr2New[i] = arr2.length > i ? Integer.parseInt(arr2[i]) : 0;
        }

        if ("<".equals(sign)) {
            for (int i = 0; i < len; i++) {
                if (arr1New[i] > arr2New[i]) {
                    return false;
                } else if (arr1New[i] < arr2New[i]) {
                    return true;
                }
            }
            return false;
        } else if (">".equals(sign)) {
            for (int i = 0; i < len; i++) {
                if (arr1New[i] > arr2New[i]) {
                    return true;
                } else if (arr1New[i] < arr2New[i]) {
                    return false;
                }
            }
            return false;
        } else if ("=".equals(sign)) {
            for (int i = 0; i < len; i++) {
                if (arr1New[i] > arr2New[i]) {
                    return false;
                } else if (arr1New[i] < arr2New[i]) {
                    return false;
                }
            }
            return true;
        }
        throw new RuntimeException("Set valid sign( '<' '>' '=' )");
    }

    /* maybe will be finished in the future */
//    private void LoadTxtInputData(String fileName) throws FileNotFoundException, UnsupportedEncodingException {
//
//        BufferedReader streamReader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName)));
//        String line;
//        int i = 1;
//
//        while (streamReader.Peek() != -1) {
//            line = streamReader.readLine();
//            System.out.println(i++);
//            System.out.println(line);
//            String[] words = line.split(":");
//            System.out.println(words.length+" words in text:");
//            for (String s : words) {
//                System.out.println(s + " | ");
//            }
//            System.out.println();
//        }
//        streamReader.close();
//    }

    /**
     * Creates Huffman Tree based on data from memory.
     * For every character in text data, a corresponding Huffman Code is prepared.
     *
     * <p>Counts every character (terminators and debug prefixes included) of the entries with a
     * non-negative id into {@link #frequencyCount}, adds one leaf per distinct character to
     * {@link #huffmanTree}, then runs {@link #BuildHuffmanTree()} and {@link #BuildCodingArray()}.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Huffman_coding">Huffman coding (Wikipedia)</a>
     */
    private void PrepareHuffmanCoding() {
        frequencyCount.clear();
        for (TlkEntry entry : inputData) {
            if (entry.stringID < 0) {
                continue;
            }
            for (char c : entry.data.toCharArray()) {
                if (!frequencyCount.containsKey(c)) {
                    frequencyCount.put(c, 0);
                }
                frequencyCount.put(c, frequencyCount.get(c) + 1);
            }
        }
        frequencyCount.forEach((key, value) -> huffmanTree.add(new HuffmanNode(key, value)));
        BuildHuffmanTree();
        BuildCodingArray();
        // DebugTools.LoadHuffmanTree(_huffmanCodes);
        // DebugTools.PrintLookupTable();
    }

    /**
     * Standard implementation of building a Huffman Tree: until one node is left, sorts
     * {@link #huffmanTree} by frequency and replaces its two least frequent nodes with a parent
     * that has the less frequent one as its left child. The root ends up at index 0.
     *
     * <p>The sort is stable, so ties are broken by list order, which comes from the
     * {@link HashMap} iteration order of {@link #frequencyCount}.
     */
    private void BuildHuffmanTree() {
        while (huffmanTree.size() > 1) {
            /* sort Huffman Nodes by frequency */
            huffmanTree.sort(HuffmanCompression::CompareNodes);
            HuffmanNode parent = new HuffmanNode(huffmanTree.get(0), huffmanTree.get(1));
            huffmanTree.remove(0);
            huffmanTree.remove(0);
            huffmanTree.add(parent);
        }
    }

    /**
     * Using the Huffman Tree (created with {@link #BuildHuffmanTree()}), generates a binary code
     * for every character into {@link #huffmanCodes}.
     *
     * <p>Note: if the input has no entry with a non-negative id, the tree is empty and this
     * throws {@link IndexOutOfBoundsException}; if it has a single distinct character, the root is
     * a leaf and that character gets an empty code.
     */
    private void BuildCodingArray() {
        /* stores a binary code */
        List<Boolean> currentCode = new LinkedList<>();
        HuffmanNode currenNode = huffmanTree.get(0);
        TraverseHuffmanTree(currenNode, currentCode);
    }

    /**
     * Recursively traverses the Huffman Tree and generates codes: the path from the root to a
     * leaf, with {@code false} for each step left and {@code true} for each step right, becomes
     * that leaf character's code.
     *
     * @param node subtree to process; a node without children is a leaf
     * @param code path from the root to {@code node}; used as a stack and restored before
     *             returning
     */
    private void TraverseHuffmanTree(HuffmanNode node, List<Boolean> code) {
        /* check if both sons are null */
        if (node.left == node.right) {
            boolean[] arr = new boolean[code.size()];
            for (int i = 0; i < code.size(); i++) {
                arr[i] = code.get(i);
            }
            BitArray ba = new BitArray(arr);
            huffmanCodes.put(node.data, ba);
        } else {
            /* adds 0 to the code - process left son*/
            code.add(false);
            TraverseHuffmanTree(node.left, code);
            code.remove(code.size() - 1);

            /* adds 1 to the code - process right son*/
            code.add(true);
            TraverseHuffmanTree(node.right, code);
            code.remove(code.size() - 1);
        }
    }

    /**
     * Converts the Huffman Tree to its binary representation used by the TLK format of Mass
     * Effect 2: one {@code (left, right)} pair of ints per inner node, in node-id order.
     *
     * <p>Ids are assigned breadth-first from the root, visiting the right child before the left
     * one: inner nodes get consecutive indices from 0, so the root is node 0 as the reader
     * expects; leaves get {@code -1 - c}, which {@link TlkFile} decodes back to the character
     * {@code c}.
     *
     * <p>Note: the pairs are emitted by iterating a {@link HashMap} keyed by node id; that yields
     * id order only because small non-negative {@link Integer} keys happen to iterate in
     * ascending order.
     *
     * @return {@code 2 * innerNodeCount} ints: left and right id of node 0, then of node 1, and
     *         so on
     */
    private List<Integer> ConvertHuffmanTreeToBuffer() {
        Queue<HuffmanNode> q = new ArrayDeque<>();
        Map<Integer, HuffmanNode> indices = new HashMap<>();

        int index = 0;
        q.add(huffmanTree.get(0));

        while (q.size() > 0) {
            HuffmanNode node = q.remove();
            /* if it's a leaf - set it's ID to reflect char data the node contains */
            if (node.left == node.right) {
                /* store the char data */
                node.id = -1 - node.data;

                /* that's how it's going to be decoded when parsing TLK file:
                 * char c = ru.BitConverter.ToChar(ru.BitConverter.GetBytes(0xffff - node.ID), 0); */
            } else {
                node.id = index++;
                indices.put(node.id, node);
            }
            if (node.right != null)
                q.add(node.right);
            if (node.left != null)
                q.add(node.left);
        }

        List<Integer> output = new LinkedList<>();

        indices.values().forEach(node -> {
            output.add(node.left.id);
            output.add(node.right.id);
        });
        return output;
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
    private static byte[] BitArrayListToByteArray(List<BitArray> bitsList, int bitsCount) {
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

    /**
     * For sorting Huffman Nodes by ascending frequency.
     *
     * @param l1 first node
     * @param l2 second node
     * @return a negative number, zero or a positive number as {@code l1}'s frequency is less
     *         than, equal to or greater than {@code l2}'s
     */
    private static int CompareNodes(HuffmanNode l1, HuffmanNode l2) {
        return Integer.compare(l1.frequencyCount, l2.frequencyCount);
    }
}