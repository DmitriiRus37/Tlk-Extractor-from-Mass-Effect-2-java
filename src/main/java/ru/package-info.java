/**
 * TLK Extractor: a JavaFX desktop tool that converts Mass Effect 2 {@code .tlk} string tables
 * (the game's compressed text, dialogue included) to editable XML and back. It is a Java port of
 * the C# <a href="https://github.com/jgoclawski/me2-tlk-tool">me2-tlk-tool</a>.
 *
 * <h2>TLK format</h2>
 * A PC TLK file consists of a 28-byte header (magic {@code "Tlk\0"}, version, minimum version,
 * male and female entry counts, Huffman tree node count, data length), a table of
 * {@code (stringId, bitOffset)} string refs (male section, then female section), a Huffman tree
 * stored as {@code (left, right)} node pairs, and a block of Huffman-coded, NUL-terminated
 * strings packed least significant bit first. All integers are little-endian 32-bit values.
 * {@link ru.TlkFile} documents the layout in detail.
 *
 * <h2>TLK to XML ("Create XML" tab)</h2>
 * <ol>
 *   <li>{@link ru.TlkFile#loadTlkData(String)} reads the header, string refs, tree and data
 *       block and decodes the text of every string ref by walking the tree bit by bit.</li>
 *   <li>{@link ru.TlkFile#storeToFile(String, ru.FileFormat, java.util.function.DoubleConsumer)}
 *       writes one {@code <string>} element per string ref, in file order, as indented UTF-8
 *       XML:
 *       <pre>{@code
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
 *       {@code <position>} is the entry's index in the string-ref table; {@code <data>} is
 *       {@code -1} for entries without text.</li>
 * </ol>
 *
 * <h2>XML to TLK ("Create Tlk" tab)</h2>
 * <ol>
 *   <li>{@link ru.HuffmanCompression#loadInputData(String, ru.FileFormat, boolean)} parses the
 *       XML (rejecting files written by tool versions before 1.0.3), sorts the entries by
 *       {@code <position>}, NUL-terminates the strings and builds a new Huffman tree and code
 *       table from their character frequencies. With the debug flag, which the GUI always sets,
 *       each localizable string is prefixed with {@code "(#<id>) "}.</li>
 *   <li>{@link ru.HuffmanCompression#saveToTlkFile(String, boolean)} encodes the strings into one
 *       bit stream and writes header, string refs, tree and data. This step is known to be
 *       incomplete: see the notes on that method.</li>
 * </ol>
 *
 * <h2>Classes</h2>
 * {@code App} starts the JavaFX application and {@code FxmlController} handles the UI defined in
 * {@code app.fxml}; both are left out of builds on a JDK 8 without JavaFX, so they are not linked
 * from here. {@link ru.BitArray} holds the compressed bits; {@link ru.BitConverter} and
 * {@link ru.PositionInputStream} are stand-ins for the .NET classes the C# original relied on.
 * {@link ru.FileFormat} lists the export formats.
 */
package ru;
