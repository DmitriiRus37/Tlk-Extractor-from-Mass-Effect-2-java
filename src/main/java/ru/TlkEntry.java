package ru;

/**
 * One string-ref entry as read from an XML, TXT or CSV file: the format-independent model that
 * {@link XmlFormat}, {@link TxtFormat} and {@link CsvFormat} produce and
 * {@link HuffmanCompression} validates and encodes.
 *
 * @param stringId string id
 * @param position index of the entry in the original string-ref table; {@link HuffmanCompression}
 *                 sorts the entries by it
 * @param data     text without terminator; {@code null} if the entry has no text
 */
record TlkEntry(int stringId, int position, String data) {
}
