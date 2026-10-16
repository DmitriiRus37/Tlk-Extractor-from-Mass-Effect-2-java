/**
 * TLK Extractor: a JavaFX desktop tool that converts Mass Effect 2 {@code .tlk} string tables
 * (the game's compressed text, dialogue included) to editable XML, TXT or CSV and back. It is a
 * Java port of the C# <a href="https://github.com/jgoclawski/me2-tlk-tool">me2-tlk-tool</a>.
 *
 * <h2>TLK format</h2>
 * A PC TLK file consists of a 28-byte header (magic {@code "Tlk\0"}, version, minimum version,
 * male and female entry counts, Huffman tree node count, data length), a table of
 * {@code (stringId, bitOffset)} string refs (male section, then female section), a Huffman tree
 * stored as {@code (left, right)} node pairs, and a block of Huffman-coded, NUL-terminated
 * strings packed least significant bit first. All integers are little-endian 32-bit values.
 * {@link ru.TlkFile} documents the layout in detail.
 *
 * <h2>Text formats</h2>
 * {@link ru.FileFormat} lists the three formats; each carries the tool version and, for every
 * string ref in file order, its id, its position, whether it has text and the text, so all three
 * convert back to the same TLK file:
 * <ul>
 *   <li>{@link ru.FileFormat#XML} ({@link ru.XmlFormat}): the {@code <tlkFile>} document of the
 *       C# original,
 *       <pre>{@code
 * <tlkFile TLKToolVersion="1.0.4">
 *     <string>
 *         <id>34931</id>
 *         <position>0</position>
 *         <data>text of the string</data>
 *     </string>
 *     ...
 * </tlkFile>
 * }</pre></li>
 *   <li>{@link ru.FileFormat#TXT} ({@link ru.TxtFormat}): a {@code # TLKToolVersion=1.0.4}
 *       header and one {@code <id>: <text>} line per entry, with backslash escapes for line
 *       breaks and {@code \N} for "no text"; line order is position order.</li>
 *   <li>{@link ru.FileFormat#CSV} ({@link ru.CsvFormat}): RFC 4180 with a
 *       {@code TLKToolVersion,1.0.4} record, the header row {@code id,position,hasText,text} and
 *       one record per entry.</li>
 * </ul>
 *
 * <h2>TLK to XML, TXT or CSV ("Create XML/TXT/CSV" tab)</h2>
 * <ol>
 *   <li>{@link ru.TlkFile#loadTlkData(String)} reads the header, string refs, tree and data
 *       block and decodes the text of every string ref by walking the tree bit by bit.</li>
 *   <li>{@link ru.TlkFile#storeToFile(String, ru.FileFormat, java.util.function.DoubleConsumer)}
 *       writes the entries in file order, as UTF-8, through the {@link ru.FormatWriter} of the
 *       chosen format.</li>
 * </ol>
 *
 * <h2>XML, TXT or CSV to TLK ("Create Tlk" tab)</h2>
 * <ol>
 *   <li>{@link ru.HuffmanCompression#loadInputData(String, ru.FileFormat, boolean)} reads the
 *       file with the reader of its format into {@link ru.TlkEntry} objects (rejecting files
 *       written by tool versions before 1.0.3), then validates and prepares them the same way for
 *       every format: CRLF becomes LF, the entries are sorted by position, no id may occur more
 *       than twice, and a new Huffman tree and code table are built from the character
 *       frequencies of the texts. With the debug flag (the GUI's "test version" check box), each
 *       localizable string is prefixed with {@code "(#<id>) "}.</li>
 *   <li>{@link ru.HuffmanCompression#saveToTlkFile(String, java.util.function.DoubleConsumer)}
 *       encodes every distinct text once into one bit stream and writes header, string refs,
 *       tree and data. Loading the result with {@link ru.TlkFile} gives back the same ids and
 *       texts in the same order.</li>
 * </ol>
 *
 * <h2>Classes</h2>
 * {@link ru.App} starts the JavaFX application and {@link ru.FxmlController} handles the UI
 * defined in {@code app.fxml}, running each conversion on a background thread. {@link ru.BitArray}
 * holds the compressed bits; {@link ru.BitConverter} and {@link ru.PositionInputStream} are
 * stand-ins for the .NET classes the C# original relied on. {@link ru.FileFormat} lists the text formats,
 * {@link ru.XmlFormat}, {@link ru.TxtFormat} and {@link ru.CsvFormat} read and write them, and
 * {@link ru.InputFile} holds what a reader returns.
 */
package ru;
