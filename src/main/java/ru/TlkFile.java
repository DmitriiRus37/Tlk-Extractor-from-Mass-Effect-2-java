package ru;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
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
 * A string ref's {@code bitOffset} is a bit index into that stream. Several refs may share an
 * offset, and an offset may point into the middle of another string to reuse its tail.
 */
public class TlkFile  {

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
     * Writes the loaded strings to a text file, deleting any existing file at that path first.
     *
     * <p>{@link FileFormat#XML} writes the {@code <tlkFile>} document (see
     * {@link #saveToXmlFile(String, DoubleConsumer)}) and then re-indents it in place
     * ({@link #prettyXmlFile(String)}); any other format writes the plain listing of
     * {@link #saveToTextFile(String, DoubleConsumer)}. Entries keep their file order (male
     * section, then female). The elapsed time in milliseconds is printed to standard output.
     *
     * @param fileName path of the output file
     * @param ff       output format
     * @param progress meant to receive the completed fraction (0 to 1), e.g. the GUI progress
     *                 bar; may be {@code null}. Currently never called. A plain
     *                 {@link DoubleConsumer} rather than the GUI controller, so that the
     *                 conversion classes compile without JavaFX
     * @throws XMLStreamException if writing the XML fails
     * @throws IOException        if the old file cannot be deleted or the new one cannot be
     *                            written or re-read
     * @throws RuntimeException   if re-indenting the XML fails; wraps the cause
     */
    public void storeToFile(String fileName, FileFormat ff, DoubleConsumer progress) throws XMLStreamException, IOException {

        long start = System.nanoTime();
        Files.deleteIfExists(Paths.get(fileName));
        /** for now, it's better not to sort, to preserve original order */
        // StringRefs.Sort(CompareTlkStringRef);

        if (ff.equals(FileFormat.XML)) {
            saveToXmlFile(fileName, progress);
            prettyXmlFile(fileName);
        } else {
            saveToTextFile(fileName, progress);
        }

        System.out.println("storeToFile: "+ (System.nanoTime() - start)/1_000_000);
    }

    /**
     * Writes all string refs as an unindented XML document.
     *
     * <p>The root is {@code <tlkFile TLKToolVersion="1.0.4">}; it holds one {@code <string>}
     * per entry with the children {@code <id>} ({@link TlkStringRef#stringId}),
     * {@code <position>} ({@link TlkStringRef#position}) and {@code <data>} (the text, or the
     * literal {@code -1} if the entry's bit offset is negative). XML comments mark the beginning
     * and end of the male and female sections. {@link HuffmanCompression} reads this format
     * back and rejects a {@code TLKToolVersion} below 1.0.3.
     *
     * <p>Note: the declaration says UTF-8 while the {@link FileWriter} uses the platform default
     * charset (UTF-8 on JDK 18 and later), and the {@code FileWriter} is never closed
     * ({@link XMLStreamWriter#close()} does not close the underlying writer).
     *
     * @param fileName path of the output file
     * @param progress meant for progress reporting (see {@link #storeToFile}); currently unused
     * @throws XMLStreamException if the XML writer fails
     * @throws IOException        if the file cannot be opened for writing
     */
    private void saveToXmlFile(String fileName, DoubleConsumer progress) throws XMLStreamException, IOException {
        int totalCount = stringRefs.size();

        // Creating FileWriter object
        Writer fileWriter = new FileWriter(fileName);

        // Getting the XMLOutputFactory instance
        XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();

        // Creating XMLStreamWriter object from xmlOutputFactory.
        XMLStreamWriter xr = xmlOutputFactory.createXMLStreamWriter(fileWriter);

        xr.writeStartDocument("utf-8", "1.0");
        xr.writeStartElement("tlkFile");
        xr.writeAttribute("TLKToolVersion", "1.0.4");
        xr.writeComment("Male entries section begin (ends at position " + (header.entry1Count - 1) + ")");

        for (TlkStringRef s : stringRefs) {
            if (s.position == header.entry1Count) {
                xr.writeComment("Male entries section end");
                xr.writeComment("Female entries section begin (ends at position " + (header.entry1Count + header.entry2Count - 1) + ")");
            }
            xr.writeStartElement("string");

            xr.writeStartElement("id");
            xr.writeCharacters(String.valueOf(s.stringId));
            xr.writeEndElement(); // </id>

            xr.writeStartElement("position");
            xr.writeCharacters(String.valueOf(s.position));
            xr.writeEndElement(); // </position>

            xr.writeStartElement("data");// </data>
            xr.writeCharacters(s.bitOffset < 0 ? "-1" : s.data);
            xr.writeEndElement(); // </data>

            xr.writeEndElement(); // </string>
        }
        xr.writeComment("Female entries section end");
        xr.writeEndElement(); // </tlkFile>
        xr.flush();
        xr.close();
    }

    /**
     * Re-indents an XML file in place, 4 spaces per level.
     *
     * <p>Reads the whole file into a string (re-joining its lines with the platform line
     * separator), parses it into a DOM and serializes it back over the same file with
     * indentation and without the XML declaration.
     *
     * <p>Note: reading and writing use the platform default charset, and the output
     * {@link PrintWriter} is never closed.
     *
     * @param fileName path of the XML file to rewrite
     * @throws IOException      if the file cannot be read
     * @throws RuntimeException if parsing, transforming or opening the file for writing fails;
     *                          the message contains the entire XML text
     */
    private void prettyXmlFile(String fileName) throws IOException {
        String xmlString;
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            StringBuilder stringBuilder = new StringBuilder();
            String ls = System.getProperty("line.separator");
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append(ls);
            }
            xmlString = stringBuilder.toString();
        }

        try {
            InputSource src = new InputSource(new StringReader(xmlString));
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(src);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", 4);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            PrintWriter writer = new PrintWriter(fileName);
            transformer.transform(new DOMSource(document), new StreamResult(writer));

        } catch (IOException | IllegalArgumentException |
                 ParserConfigurationException | TransformerException | SAXException e) {
            throw new RuntimeException("Error occurs when pretty-printing xml:\n" + xmlString, e);
        }
    }

    /**
     * Writes all string refs as plain text: one {@code "<stringId>: <data>"} line per entry,
     * terminated by CRLF ({@code data} prints as {@code null} for entries without text).
     *
     * <p>Currently not used by main application, but it works ok. Note: the file is opened in
     * append mode once per line, and write errors are printed and otherwise ignored.
     *
     * @param fileName path of the output file; appended to
     * @param progress meant for progress reporting (see {@link #storeToFile}); currently unused
     */
    private void saveToTextFile(String fileName, DoubleConsumer progress) {
        int totalCount = stringRefs.size();

        for (TlkStringRef s : stringRefs) {
            String line = s.stringId + ": " + s.data + "\r\n";

            try (FileWriter fw = new FileWriter(fileName, true)) {
                fw.write(line);
            } catch (IOException e) {
                e.printStackTrace();
            }
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
     * The fixed 28-byte header at the start of a TLK file: seven little-endian ints, read in the
     * order of the fields below.
     */
    public static class TlkHeader {
        /** Size of the header in bytes. */
        public static final int SIZE = 7 * Integer.BYTES;
        /** Magic number of a PC TLK file: {@code 0x006B6C54}, the bytes {@code "Tlk\0"}. */
        public static final int MAGIC = 7040084;
        /** What {@link #MAGIC} reads as in a big-endian (Xbox 360) file. */
        public static final int XBOX_MAGIC = 1416391424;

        /** File signature; {@link #MAGIC} for a PC file. */
        public int magic;
        /** Format version; 3 in ME2 files, and the value {@link HuffmanCompression} writes. */
        public int ver;
        /** Minimum version; 2 in ME2 files, and the value {@link HuffmanCompression} writes. */
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
            this.magic = TlkFile.readInt32(r);
            this.ver = TlkFile.readInt32(r);
            this.minVer = TlkFile.readInt32(r);
            this.entry1Count = TlkFile.readInt32(r);
            this.entry2Count = TlkFile.readInt32(r);
            this.treeNodeCount = TlkFile.readInt32(r);
            this.dataLen = TlkFile.readInt32(r);
        }

        /**
         * Returns the size of a file with this header: header, string refs, tree and data.
         *
         * @return the size in bytes
         */
        public long expectedFileSize() {
            return SIZE + 8L * entry1Count + 8L * entry2Count + 8L * treeNodeCount + dataLen;
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
         * {@link #bitOffset} of {@code -1}, i.e. without text; the XML round trip relies on that.
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
            this.stringId = TlkFile.readInt32(r);
            this.bitOffset = TlkFile.readInt32(r);
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
            this.leftNodeId = TlkFile.readInt32(r);
            this.rightNodeId = TlkFile.readInt32(r);
        }
    }
}
