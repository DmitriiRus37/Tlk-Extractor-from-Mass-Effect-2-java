package ru;

import java.util.Locale;

/**
 * Text formats a TLK string table can be exported to and imported from.
 *
 * <p>All three are complete and can be converted in both directions:
 * {@link TlkFile#storeToFile(String, FileFormat, java.util.function.DoubleConsumer)} writes them
 * and {@code HuffmanCompression} reads them back. Each one carries the tool version and, for
 * every string ref in file order, its id, its position, whether it has text and the text, so a
 * TLK file converted to any of them and back gives the same entries again. All are UTF-8.
 */
public enum FileFormat {
    /**
     * Plain text for any text editor: a {@code # TLKToolVersion=<version>} header line, then one
     * {@code <id>: <text>} line per entry in file order (line order is position order), with
     * {@code \n}, {@code \r}, {@code \t} and {@code \\} escapes and {@code \N} for an entry
     * without text. See {@code TxtFormat}.
     */
    TXT("txt"),
    /**
     * RFC 4180 comma-separated values for spreadsheets: a {@code TLKToolVersion,<version>}
     * record, the header row {@code id,position,hasText,text} and one record per entry, with a
     * byte order mark for Excel. See {@code CsvFormat}.
     */
    CSV("csv"),
    /**
     * The {@code <tlkFile>} XML document of the C# original, with one {@code <string>} element
     * per entry. See {@code XmlFormat}.
     */
    XML("xml");

    /** File name extension, lower case, without the dot. */
    private final String extension;

    /**
     * Creates a constant.
     *
     * @param extension file name extension, lower case, without the dot
     */
    FileFormat(String extension) {
        this.extension = extension;
    }

    /**
     * Returns the usual file name extension of the format.
     *
     * @return the extension in lower case, without the dot, e.g. {@code "xml"}
     */
    public String getExtension() {
        return extension;
    }

    /**
     * Returns the format whose extension a file name has, ignoring case.
     *
     * @param fileName a file name or path, e.g. {@code "out/BIOGame_RUS.CSV"}; may be {@code null}
     * @return the format, or {@code null} if the name is {@code null} or has none of the
     *         extensions
     */
    public static FileFormat fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim().toLowerCase(Locale.ROOT);
        for (FileFormat format : values()) {
            if (name.endsWith("." + format.extension)) {
                return format;
            }
        }
        return null;
    }
}
