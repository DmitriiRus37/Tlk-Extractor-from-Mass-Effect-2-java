package ru;

/**
 * Text formats a TLK string table can be exported to, or imported from.
 *
 * <p>Only {@link #XML} is fully supported and it is the only one the GUI uses:
 * {@link TlkFile#storeToFile(String, FileFormat, java.util.function.DoubleConsumer)} writes
 * {@link #TXT} and {@link #CSV} as the same plain {@code "<id>: <text>"} listing, and
 * {@code HuffmanCompression} only reads {@link #XML}.
 */
public enum FileFormat {
    /** Plain text, one {@code "<id>: <text>"} line per entry. Export only. */
    TXT,
    /** Not implemented: export produces the {@link #TXT} listing (no CSV quoting or separators). */
    CSV,
    /** The {@code <tlkFile>} XML document; the only format that can be converted back to TLK. */
    XML
}
