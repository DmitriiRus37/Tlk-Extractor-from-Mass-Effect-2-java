package ru;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The content of a file read by {@link XmlFormat}, {@link TxtFormat} or {@link CsvFormat}: the
 * tool version it declares and its entries in file order, with the texts exactly as stored (no
 * line-break normalization, no debug prefix; {@link HuffmanCompression} applies those to every
 * format alike). Also holds small helpers shared by the TXT and CSV readers.
 *
 * @param toolVersion {@code TLKToolVersion} declared by the file
 * @param entries     the entries in file order
 */
record InputFile(String toolVersion, List<TlkEntry> entries) {

    /** Byte order mark, which some editors (Notepad, Excel) put at the start of UTF-8 files. */
    static final char BOM = '﻿';
    /** Longest piece of a malformed line quoted in an error message. */
    private static final int MAX_QUOTE = 40;

    /**
     * Reads a whole UTF-8 text file, dropping a leading byte order mark.
     *
     * @param file the file
     * @return its content
     * @throws IOException if the file cannot be read or is not valid UTF-8
     */
    static String readUtf8(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Not a UTF-8 text file (" + e + "): " + file, e);
        }
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /**
     * Creates the exception for malformed input.
     *
     * @param problem what is wrong
     * @param line    1-based line number
     * @param file    the file
     * @return an exception with the message {@code "<problem> (line <line> of <file>)"}
     */
    static IOException error(String problem, int line, Path file) {
        return new IOException(problem + " (line " + line + " of " + file + ")");
    }

    /**
     * Quotes a piece of input for an error message, shortened to {@value #MAX_QUOTE} characters.
     *
     * @param text the input
     * @return the text in double quotes, with {@code "..."} appended if it was shortened
     */
    static String quote(String text) {
        return "\"" + (text.length() <= MAX_QUOTE ? text : text.substring(0, MAX_QUOTE) + "...") + "\"";
    }

    /**
     * Parses a decimal 32-bit integer such as an id or a position, without surrounding
     * whitespace or a plus sign.
     *
     * @param text  the digits, optionally preceded by {@code '-'}
     * @param what  name of the value, for the error message
     * @param line  1-based line number, for the error message
     * @param file  the file, for the error message
     * @return the value
     * @throws IOException if {@code text} is not such an integer
     */
    static int parseInt(String text, String what, int line, Path file) throws IOException {
        int start = text.startsWith("-") ? 1 : 0;
        boolean digits = text.length() > start;
        for (int i = start; i < text.length() && digits; i++) {
            digits = text.charAt(i) >= '0' && text.charAt(i) <= '9';
        }
        if (digits) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                // out of range, reported below
            }
        }
        throw error("Invalid " + what + " " + quote(text) + " (not a 32-bit integer)", line, file);
    }
}
