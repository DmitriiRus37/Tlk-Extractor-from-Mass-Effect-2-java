package ru;

/**
 * One string-ref entry as read from an XML, TXT or CSV file: the format-independent model that
 * {@link XmlFormat}, {@link TxtFormat} and {@link CsvFormat} produce and
 * {@link HuffmanCompression} validates and encodes.
 */
final class TlkEntry {
    /** String id. */
    final int stringId;
    /**
     * Index of the entry in the original string-ref table; {@link HuffmanCompression} sorts the
     * entries by it.
     */
    final int position;
    /** Text without terminator; {@code null} if the entry has no text. */
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
