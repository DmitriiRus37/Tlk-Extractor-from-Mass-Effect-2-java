package ru;

import lombok.Getter;
import lombok.Setter;
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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Reads a Mass Effect 2 {@code .tlk} string table and writes it out as XML (or plain text): the
 * "Create XML" half of the tool.
 *
 * <p>Usage: {@link #loadTlkData(String, boolean)} decodes a file into memory, then
 * {@link #storeToFile(String, FileFormat, FxmlController)} writes it. {@link HuffmanCompression}
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

    /** Header of the loaded file; {@code null} until {@link #loadTlkData(String, boolean)} runs. */
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
     * Creates an empty instance; call {@link #loadTlkData(String, boolean)} before
     * {@link #storeToFile(String, FileFormat, FxmlController)}.
     */
    public TlkFile() {
    }

    /**
     * Loads a TLK file into memory, replacing anything loaded before.
     *
     * <p>Steps (numbered like the comments in the code): (1) read the {@linkplain TlkHeader header}
     * and check its magic number; (2) skip the string refs and read the Huffman tree; (3) read the
     * compressed data; (4) decode <em>every</em> string of the data block in sequence, keyed by
     * its starting bit offset; (5) go back to the string refs and attach to each the text found at
     * its bit offset. An offset that does not start one of the strings from step 4 points into
     * the middle of one (shared tail) and is decoded separately, which yields just that tail.
     * Entries with a negative bit offset keep {@code null} text. The elapsed time in milliseconds
     * is printed to standard output.
     *
     * <p>Note: every "seek" opens a new stream (see {@link #baseStreamSeek(long, String)}) and only
     * the last of the three is closed. The data block is read with a single
     * {@link java.io.InputStream#read(byte[], int, int)} call whose result is not checked, so a
     * short read or truncated file leaves the rest of the block zero-filled.
     *
     * @param fileName path of the {@code .tlk} file
     * @param isPC     meant to choose little-endian (PC) or big-endian (Xbox 360) byte order, as in
     *                 the C# original; currently ignored, the file is always read as little-endian
     * @throws IOException      if the file cannot be opened or read, or ends inside the header,
     *                          the string refs or the tree ({@link EOFException})
     * @throws RuntimeException (without a message) if the magic number is not that of a PC TLK
     *                          file; this includes Xbox 360 files, whose big-endian magic reads
     *                          as {@code 1416391424}
     */
    public void loadTlkData(String fileName, boolean isPC) throws IOException {
        long start = System.nanoTime();
        /** **************** STEP ONE ****************
         *          -- load TLK file header --
         *        reading first 28 (4 * 7) bytes
         */

        /** using LittleEndian for PC architecture and BigEndian for Xbox360 */
        PositionInputStream r = baseStreamSeek( 0, fileName);

        header = new TlkHeader(r);
        /** read possibly correct ME2 TLK file, but from another platfrom */
        if (header.magic == 1416391424) {
            throw new RuntimeException();
        }
        /** read definitely NOT a ME2 TLK ile */
        if (header.magic != 7040084) {
            throw new RuntimeException();
        }
        //DebugTools.PrintHeader(Header);

        /** **************** STEP TWO ****************
         *  -- read and store Huffman Tree nodes --
         */
        /** jumping to the beginning of Huffmann Tree stored in TLK file */
        long pos = r.getPosition();
        r = baseStreamSeek( pos + (header.entry1Count + header.entry2Count)*8L, fileName);

        characterTree = new LinkedList<>();
        for (int i = 0; i < header.treeNodeCount; i++) {
            characterTree.add(new HuffmanNode(r));
        }

        /** **************** STEP THREE ****************
         *  -- read all of coded data into memory --
         */
        byte[] data = new byte[header.dataLen];
        r.read(data,0, data.length);
        /** and store it as raw bits for further processing */
        bits = new BitArray(data);
        /** rewind BinaryReader just after the Header
         * at the beginning of TLK Entries data
         * */
        r = baseStreamSeek(pos, fileName);

        /** **************** STEP FOUR ****************
         * -- decode (basing on Huffman Tree) raw bits data into actual strings --
         * and store them in a Dictionary<int, string> where:
         *   int: bit offset of the beginning of data (offset starting at 0 and counted for Bits array)
         *        so offset == 0 means the first bit in Bits array
         *   string: actual decoded String
         */
        Map<Integer, String> rawStrings = new HashMap<>();
        int offset = 0;
        Wrap offsetWrap = new Wrap(offset);
        while (offsetWrap.getValue() < bits.length()) {
            int key = offsetWrap.getValue();
            /** read the String and update 'offset' variable to store NEXT String offset */
            String s = GetString(offsetWrap);
            rawStrings.put(key, s);
        }

        /** **************** STEP FIVE ****************
         *         -- bind data to String IDs --
         * go through Entries in TLK file and read it's String ID and offset
         * then check if offset is a key in rawStrings and if it is, then bind data.
         * Sometimes there's no such key, in that case, our String ID is probably a substring
         * of another String present in rawStrings.
         */
        stringRefs = new LinkedList<>();
        for (int i = 0; i < header.entry1Count + header.entry2Count; i++) {
            TlkStringRef sRef = new TlkStringRef(r);
            sRef.position = i;
            if (sRef.bitOffset >= 0) {
                /** actually, it should store the fullString and subStringOffset,
                 * but as we don't have to use this compression feature,
                 * we will store only the part of String we need
                 * */
                /** int key = rawStrings.Keys.Last(c => c < sRef.BitOffset);
                 * String fullString = rawStrings[key];
                 * int subStringOffset = fullString.LastIndexOf(partString);
                 * sRef.StartOfString = subStringOffset;
                 * sRef.Data = fullString;
                 */
                sRef.data = rawStrings.containsKey(sRef.bitOffset) ?
                    rawStrings.get(sRef.bitOffset) :
                    GetString(new Wrap(sRef.bitOffset));
            }
            stringRefs.add(sRef);
        }
        r.close();
        System.out.println("loadTlkData: "+ (System.nanoTime() - start)/1_000_000);
    }

    /**
     * Writes the loaded strings to a text file, deleting any existing file at that path first.
     *
     * <p>{@link FileFormat#XML} writes the {@code <tlkFile>} document (see
     * {@link #saveToXmlFile(String, FxmlController)}) and then re-indents it in place
     * ({@link #prettyXmlFile(String)}); any other format writes the plain listing of
     * {@link #saveToTextFile(String, FxmlController)}. Entries keep their file order (male
     * section, then female). The elapsed time in milliseconds is printed to standard output.
     *
     * @param fileName   path of the output file
     * @param ff         output format
     * @param controller the UI controller, meant for progress reporting; currently unused
     * @throws XMLStreamException if writing the XML fails
     * @throws IOException        if the old file cannot be deleted or the new one cannot be
     *                            written or re-read
     * @throws RuntimeException   if re-indenting the XML fails; wraps the cause
     */
    public void storeToFile(String fileName, FileFormat ff, FxmlController controller) throws XMLStreamException, IOException {

        long start = System.nanoTime();
        Files.deleteIfExists(Paths.get(fileName));
        /** for now, it's better not to sort, to preserve original order */
        // StringRefs.Sort(CompareTlkStringRef);

        if (ff.equals(FileFormat.XML)) {
            saveToXmlFile(fileName, controller);
            prettyXmlFile(fileName);
        } else {
            saveToTextFile(fileName, controller);
        }

        System.out.println("storeToFile: "+ (System.nanoTime() - start)/1_000_000);
    }

    /**
     * Decodes one NUL-terminated string from {@link #bits} by walking {@link #characterTree}.
     *
     * <p>Starting at the root, each bit (read LSB-first with {@link BitArray#getRev(int)}) selects
     * the right child if it is 1 and the left child if it is 0. A non-negative child id is the
     * index of an inner node; a negative id {@code v} is a leaf for the character
     * {@code (char) (-1 - v)}, after which decoding restarts at the root. The {@code '\0'} leaf
     * ends the string.
     *
     * <p>The character is computed as the low 16 bits of {@code 0xffff - v} using
     * {@link BitConverter#GetBytes(int)} and {@link BitConverter#toCharRev(byte[], int)}, which
     * gives the right result only on a little-endian platform. Should the conversion throw, the
     * stack trace is printed and the character is treated as the terminator.
     *
     * @param bitOffsetWrap in: bit index to start decoding at; out: index of the first bit after
     *                      the terminator's code, i.e. where the next string starts (or
     *                      {@code bits.length() + 1} if the data ran out first)
     * @return the decoded string without its terminator, or {@code null} if the data ended
     *         before a terminator was found (e.g. in the zero padding after the last string)
     */
    private String GetString(Wrap bitOffsetWrap) {
        HuffmanNode root = characterTree.get(0);
        HuffmanNode curNode = root;
        String curString = "";
        int i;
        for (i = bitOffsetWrap.getValue(); i < bits.length(); i++) {
            /** reading bits' sequence and decoding it to Strings while traversing Huffman Tree */
            int nextNodeID = bits.getRev(i) ? curNode.rightNodeId : curNode.leftNodeId;

            if (nextNodeID >= 0) {
                /** it's an internal node - keep looking for a leaf */
                curNode = characterTree.get(nextNodeID);
            } else {
                /** it's a leaf! */
                char c = 0;
                try {
                    c = BitConverter.toCharRev(BitConverter.GetBytes(0xffff - nextNodeID), 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (c != '\0') {
                    /** it's not NULL */
                    curString += c;
                    curNode = root;
                } else {
                    /** it's a NULL terminating processed string, we're done */
                    i++;
                    bitOffsetWrap.setValue(i);
                    return curString;
                }
            }
        }
        i++;
        bitOffsetWrap.setValue(i);
        return null;
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
     * @param fileName   path of the output file
     * @param controller the UI controller, meant for progress reporting; currently unused
     * @throws XMLStreamException if the XML writer fails
     * @throws IOException        if the file cannot be opened for writing
     */
    private void saveToXmlFile(String fileName, FxmlController controller) throws XMLStreamException, IOException {
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

        for (int i = 0; i < stringRefs.size(); i++) {
            TlkStringRef s = stringRefs.get(i);
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
     * @param fileName   path of the output file; appended to
     * @param controller the UI controller, meant for progress reporting; currently unused
     */
    private void saveToTextFile(String fileName, FxmlController controller) {
        int totalCount = stringRefs.size();

        for (int i = 0; i < stringRefs.size(); i++) {
            TlkStringRef s = stringRefs.get(i);
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
            throw new EOFException();
        }
        return (ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1;
    }

    /**
     * Opens a file and skips to a byte offset; the stand-in for C# {@code BaseStream.Seek},
     * since a Java input stream cannot move backwards.
     *
     * <p>The caller owns the returned stream and must close it. Note: the result of
     * {@link PositionInputStream#skip(long)} is not checked, so the stream may end up before
     * {@code pos} if the skip comes up short.
     *
     * @param pos      byte offset from the start of the file
     * @param fileName path of the file
     * @return a new stream whose {@linkplain PositionInputStream#getPosition() position} is the
     *         number of bytes skipped
     * @throws IOException if the file cannot be opened or skipping fails
     */
    public static PositionInputStream baseStreamSeek(long pos, String fileName) throws IOException {
        PositionInputStream r = new PositionInputStream(Files.newInputStream(Paths.get(fileName)));
        r.skip(pos);
        return r;
    }

//        /** for sorting */
//        private static int CompareTlkStringRef(ru.TlkHeader.ru.TlkStringRef strRef1, ru.TlkHeader.ru.TlkStringRef strRef2) {
//            int result = strRef1.stringId.compareTo(strRef2.stringId);
//            return result;
//        }

    /**
     * The fixed 28-byte header at the start of a TLK file: seven little-endian ints, read in the
     * order of the fields below.
     */
    public static class TlkHeader {
        /**
         * File signature: {@code 7040084} ({@code 0x006B6C54}, the bytes {@code "Tlk\0"}) for a
         * PC file. The only field that is validated.
         */
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
         * Not stored in the file; set by {@link TlkFile#loadTlkData(String, boolean)}.
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
     * leaf for the character {@code (char) (-1 - v)}, so {@code -1} is the {@code '\0'}
     * terminator and {@code -33} is a space. While decoding, a 0 bit follows {@link #leftNodeId}
     * and a 1 bit follows {@link #rightNodeId}.
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

    /**
     * Mutable {@code int} holder standing in for the C# {@code ref int} parameter of the
     * original {@code GetString}, so that {@link TlkFile#GetString(Wrap)} can hand back the next
     * bit offset along with the decoded string. Lombok generates {@code getValue()} and
     * {@code setValue(int)}.
     */
    @Getter @Setter
    public static class Wrap {
        /** The wrapped value; a bit offset into the compressed data where this class is used. */
        int value;

        /** Creates a holder with value {@code 0}. */
        public Wrap() {
        }

        /**
         * Creates a holder with the given value.
         *
         * @param value the initial value
         */
        public Wrap(int value) {
            this.value = value;
        }
    }

}

