package ru;

/**
 * Text formats a TLK string table can be exported to, or imported from.
 *
 * <p>Only {@link #XML} is really supported and it is the only one the GUI uses:
 * {@link TlkFile#storeToFile(String, FileFormat, FxmlController)} writes every other value as the
 * same plain {@code "<id>: <text>"} listing, and {@code HuffmanCompression} ignores the format it
 * is given and always parses XML.
 */
public enum FileFormat {
    /** Plain text, one {@code "<id>: <text>"} line per entry. Export only. */
    TXT,
    /** Not implemented: export produces the {@link #TXT} listing (no CSV quoting or separators). */
    CSV,
    /** The {@code <tlkFile>} XML document; the only format that can be converted back to TLK. */
    XML
}
