package ru;

import java.io.IOException;

/**
 * Writes the string refs of a TLK file in one {@link FileFormat}. {@link TlkFile} drives it:
 * {@link #begin(String)} once, then {@link #comment(String)} and {@link #entry(TlkFile.TlkStringRef)}
 * in file order (section comments before, between and after the entries), then {@link #end()}.
 * Implementations write to a character stream that the caller opens and closes.
 */
interface FormatWriter {

    /**
     * Writes whatever comes before the first entry, such as a header naming the tool version.
     *
     * @param toolVersion version of the tool writing the file, {@value TlkFile#TOOL_VERSION}
     * @throws IOException if writing fails
     */
    void begin(String toolVersion) throws IOException;

    /**
     * Writes a comment line that readers ignore, such as the start of the female section;
     * formats without comments ignore it.
     *
     * @param text the comment; contains no line break and no {@code "--"}
     * @throws IOException if writing fails
     */
    void comment(String text) throws IOException;

    /**
     * Writes one entry.
     *
     * @param ref the entry: its {@link TlkFile.TlkStringRef#stringId},
     *            {@link TlkFile.TlkStringRef#position} and {@link TlkFile.TlkStringRef#data}
     *            ({@code null} if it has no text)
     * @throws IOException if writing fails or the text cannot be represented in the format; the
     *                     message names the entry
     */
    void entry(TlkFile.TlkStringRef ref) throws IOException;

    /**
     * Writes whatever comes after the last entry.
     *
     * @throws IOException if writing fails
     */
    void end() throws IOException;
}
