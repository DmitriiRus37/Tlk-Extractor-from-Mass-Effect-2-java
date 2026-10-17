package ru;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TxtFormat}: escaping, the writer and the reader with its error messages. */
class TxtFormatTest {

    private static final String HEADER = "# TLKToolVersion=1.0.4\n";

    @TempDir
    Path dir;

    private int fileCounter;

    private Path file(String content) throws IOException {
        return TestSupport.writeUtf8(dir.resolve("in" + (fileCounter++) + ".txt"), content);
    }

    private InputFile read(String content) throws IOException {
        return TxtFormat.read(file(content));
    }

    /** Asserts that reading fails with a message containing {@code text} and naming the line. */
    private IOException assertReadFails(String content, String text, int line) throws IOException {
        Path f = file(content);
        IOException e = assertThrows(IOException.class, () -> TxtFormat.read(f));
        assertTrue(e.getMessage().contains(text), e.getMessage());
        assertTrue(e.getMessage().endsWith("(line " + line + " of " + f + ")"), e.getMessage());
        return e;
    }

    private static List<String> texts(InputFile in) {
        List<String> result = new ArrayList<>();
        for (TlkEntry e : in.entries()) {
            result.add(e.data());
        }
        return result;
    }

    private static List<Integer> ids(InputFile in) {
        List<Integer> result = new ArrayList<>();
        for (TlkEntry e : in.entries()) {
            result.add(e.stringId());
        }
        return result;
    }

    private static TlkFile.TlkStringRef ref(int id, int position, String data) {
        TlkFile.TlkStringRef r = new TlkFile.TlkStringRef(id, data == null ? -1 : 0);
        r.position = position;
        r.data = data;
        return r;
    }

    @Nested
    class Escaping {

        @Test
        void plainTextIsReturnedAsIs() {
            String text = "Привет, мир! \"quoted\" 100% #1: ok";
            assertSame(text, TxtFormat.escape(text));
        }

        @Test
        void backslashAndLineBreaksAreEscaped() {
            assertEquals("a\\\\b\\nc\\r\\nd\\re\\tf", TxtFormat.escape("a\\b\nc\r\nd\re\tf"));
            assertEquals("\\\\N", TxtFormat.escape("\\N"));
        }

        @Test
        void controlCharactersAndSeparatorsUseUnicodeEscapes() {
            assertEquals("\\u0001\\u001F\\u007F\\u0085\\u009F\\u2028\\u2029",
                    TxtFormat.escape("\u0001\u001F\u007F\u0085\u009F\u2028\u2029"));
        }

        @Test
        void unpairedSurrogatesAreEscapedButPairsAreKept() {
            String emoji = "\uD83D\uDE00";
            assertSame(emoji, TxtFormat.escape(emoji));
            assertEquals("x" + emoji + "\\n", TxtFormat.escape("x" + emoji + "\n"));
            assertEquals("\\uD83Dx", TxtFormat.escape("\uD83Dx"));
            assertEquals("a\\uD83D", TxtFormat.escape("a\uD83D"));
            assertEquals("\\uDE00a", TxtFormat.escape("\uDE00a"));
            assertEquals("\\uDE00\\uD83D", TxtFormat.escape("\uDE00\uD83D"));
        }

        @Test
        void unescapeIsTheInverseForRandomTexts() throws IOException {
            Random random = new Random(42);
            char[] alphabet = "ab \\\n\r\t\u0000\u0007\u0085\u2028Жё\uD83D\uDE00\uFFFF:#\"N".toCharArray();
            for (int round = 0; round < 2000; round++) {
                StringBuilder sb = new StringBuilder();
                int length = random.nextInt(12);
                for (int i = 0; i < length; i++) {
                    sb.append(alphabet[random.nextInt(alphabet.length)]);
                }
                String text = sb.toString();
                String escaped = TxtFormat.escape(text);
                assertTrue(escaped.indexOf('\n') < 0 && escaped.indexOf('\r') < 0 && escaped.indexOf('\u2028') < 0, escaped);
                assertEquals(text, TxtFormat.unescape(escaped, 0, 1, dir));
            }
        }

        @Test
        void unicodeEscapesAcceptAnyHexDigits() throws IOException {
            assertEquals("A\u00e9\uD83D\uDE00", TxtFormat.unescape("\\u0041\\u00e9\\uD83D\\ude00", 0, 1, dir));
        }
    }

    @Nested
    class Writing {

        private String write(TlkFile.TlkStringRef... refs) throws IOException {
            StringWriter out = new StringWriter();
            FormatWriter writer = TxtFormat.writer(out);
            writer.begin("1.0.4");
            writer.comment("a comment");
            for (TlkFile.TlkStringRef r : refs) {
                writer.entry(r);
            }
            writer.end();
            return out.toString();
        }

        @Test
        void writesHeaderCommentsAndOneLinePerEntry() throws IOException {
            assertEquals("# TLKToolVersion=1.0.4\r\n# a comment\r\n"
                            + "1: Hello\r\n-2: \\N\r\n3: \r\n4: two\\nlines\r\n-6: has text\r\n7: \\N\r\n",
                    write(ref(1, 0, "Hello"), ref(-2, 1, null), ref(3, 2, ""), ref(4, 3, "two\nlines"),
                            ref(-6, 5, "has text"), ref(7, 6, null)));
        }

        @Test
        void literalBackslashNIsNotTheNoTextMarker() throws IOException {
            assertEquals("# TLKToolVersion=1.0.4\r\n# a comment\r\n8: \\\\N\r\n", write(ref(8, 0, "\\N")));
        }
    }

    @Nested
    class Reading {

        @Test
        void readsIdsTextsAndLineOrderAsPositions() throws IOException {
            InputFile in = read(HEADER + "100: Hello\n-7: \\N\n101: Привет, мир!\n100: two\\nlines\n");
            assertEquals("1.0.4", in.toolVersion());
            assertEquals(Arrays.asList(100, -7, 101, 100), ids(in));
            assertEquals(Arrays.asList("Hello", null, "Привет, мир!", "two\nlines"), texts(in));
            for (int i = 0; i < in.entries().size(); i++) {
                assertEquals(i, in.entries().get(i).position());
            }
        }

        @Test
        void emptyTextVersusNoText() throws IOException {
            InputFile in = read(HEADER + "1: \n2:\n3: \\N\n4:\\N\n5:  \n6: \\\\N\n");
            assertEquals(Arrays.asList("", "", null, null, " ", "\\N"), texts(in));
        }

        @Test
        void onlyOneSpaceAfterTheColonIsSkipped() throws IOException {
            InputFile in = read(HEADER + "1:  two spaces \n2:x\n3: a: b\n");
            assertEquals(Arrays.asList(" two spaces ", "x", "a: b"), texts(in));
        }

        @Test
        void commentsAndBlankLinesAreSkipped() throws IOException {
            InputFile in = read(HEADER + "# Male entries section begin\n\n   \n\t\n1: a\n#2: not an entry\n2: b\n\n\n");
            assertEquals(Arrays.asList(1, 2), ids(in));
            assertEquals(1, in.entries().get(1).position());
        }

        @Test
        void allLineEndsAndAMissingLastLineEndGiveTheSameEntries() throws IOException {
            String lf = HEADER + "1: a\\r\\nb\n2: \\N\n3: c";
            InputFile expected = read(lf);
            for (String content : Arrays.asList(lf.replace("\n", "\r\n"), lf.replace("\n", "\r"), lf + "\n", lf + "\r\n")) {
                InputFile in = read(content);
                assertEquals(ids(expected), ids(in));
                assertEquals(texts(expected), texts(in));
            }
            assertEquals(Arrays.asList("a\r\nb", null, "c"), texts(expected));
        }

        @Test
        void largeFileWithCarriageReturnLineEndsIsReadInLinearTime() throws IOException {
            // looking for the next line feed from every line would scan this file ~10^10 times
            StringBuilder content = new StringBuilder(HEADER.replace("\n", "\r"));
            for (int i = 0; i < 200_000; i++) {
                content.append(i).append(": text ").append(i).append('\r');
            }
            long start = System.nanoTime();
            InputFile in = read(content.toString());
            assertEquals(200_000, in.entries().size());
            assertEquals("text 199999", in.entries().get(199_999).data());
            assertTrue(System.nanoTime() - start < 10_000_000_000L, "took " + (System.nanoTime() - start) / 1e9 + " s");
        }

        @Test
        void byteOrderMarkIsOptional() throws IOException {
            Path withBom = dir.resolve("bom.txt");
            byte[] content = (HEADER + "1: Жёлтый\n").getBytes(StandardCharsets.UTF_8);
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] both = new byte[bom.length + content.length];
            System.arraycopy(bom, 0, both, 0, bom.length);
            System.arraycopy(content, 0, both, bom.length, content.length);
            Files.write(withBom, both);
            assertEquals(Collections.singletonList("Жёлтый"), texts(TxtFormat.read(withBom)));
            assertEquals(Collections.singletonList("Жёлтый"), texts(read(HEADER + "1: Жёлтый\n")));
        }

        @Test
        void headerMayHaveSpacesAndANewerVersion() throws IOException {
            assertEquals("2.0", read("#  TLKToolVersion = 2.0 \n1: a\n").toolVersion());
            assertEquals("1.0.3", read("#TLKToolVersion=1.0.3").toolVersion());
            assertTrue(read("#TLKToolVersion=1.0.3").entries().isEmpty());
        }

        @Test
        void extremeIds() throws IOException {
            InputFile in = read(HEADER + "2147483647: max\n-2147483648: \\N\n0: zero\n");
            assertEquals(Arrays.asList(Integer.MAX_VALUE, Integer.MIN_VALUE, 0), ids(in));
        }
    }

    @Nested
    class Errors {

        @Test
        void emptyFile() throws IOException {
            assertReadFails("", "Missing header \"# TLKToolVersion=<version>\" in the first line, found \"\"", 1);
        }

        @Test
        void oldExportWithoutHeader() throws IOException {
            assertReadFails("100: Hello\r\n-7: null\r\n", "found \"100: Hello\" (plain-text exports of older versions"
                    + " cannot be imported)", 1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"# TLKToolVersion\n", "# TLKToolVersion=\n", "# Version=1.0.4\n", "TLKToolVersion=1.0.4\n",
                "# TLKToolVersion 1.0.4\n", "\n# TLKToolVersion=1.0.4\n"})
        void malformedHeader(String content) throws IOException {
            assertReadFails(content, "Missing header", 1);
        }

        @Test
        void tooOldOrInvalidVersion() throws IOException {
            Path old = file("# TLKToolVersion=1.0.2\n1: a\n");
            IOException e = assertThrows(IOException.class, () -> TxtFormat.read(old));
            assertEquals("The TXT file was written by TLK tool version 1.0.2; files from versions before 1.0.3 are not"
                    + " supported: " + old, e.getMessage());
            Path invalid = file("# TLKToolVersion=1.x\n");
            e = assertThrows(IOException.class, () -> TxtFormat.read(invalid));
            assertEquals("Invalid TLKToolVersion \"1.x\": " + invalid, e.getMessage());
        }

        @Test
        void lineWithoutColon() throws IOException {
            assertReadFails(HEADER + "1: ok\n\nno colon here\n", "Expected \"<id>: <text>\" but found \"no colon here\"", 4);
        }

        @Test
        void longLinesAreShortenedInMessages() throws IOException {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                line.append('x');
            }
            IOException e = assertReadFails(HEADER + line + "\n", "found \"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx...\"", 2);
            assertTrue(e.getMessage().length() < 200, e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc: x", " 5: x", "5 : x", "+5: x", "-: x", ": x", "2147483648: x", "1.5: x", "٣: x"})
        void invalidId(String line) throws IOException {
            String id = line.substring(0, line.indexOf(':'));
            assertReadFails(HEADER + line + "\n", "Invalid id \"" + id + "\" (not a 32-bit integer)", 2);
        }

        @Test
        void invalidEscapes() throws IOException {
            assertReadFails(HEADER + "1: a\\xb\n", "Invalid escape sequence \"\\x\" at column 5", 2);
            assertReadFails(HEADER + "1: \\N and more\n", "\\N for \"no text\" must be the whole text", 2);
            assertReadFails(HEADER + "1: a\\Nb\n", "Invalid escape sequence \"\\N\" at column 5", 2);
            assertReadFails(HEADER + "1: \\\\N\\\n", "Incomplete escape sequence", 2);
            assertReadFails(HEADER + "1:\\\n", "Incomplete escape sequence", 2);
            assertReadFails(HEADER + "1: \\u12\n", "Invalid escape sequence at column 4 (\\u must be followed by 4 hex digits)", 2);
            assertReadFails(HEADER + "1: ab\\u12G4\n", "at column 6", 2);
            assertReadFails(HEADER + "22: \\u١٢٣٤\n", "at column 5", 2);
        }

        @Test
        void errorsAfterCrLfAndCrLinesNameTheRightLine() throws IOException {
            assertReadFails((HEADER + "1: a\n# c\n2 x\n").replace("\n", "\r\n"), "Expected", 4);
            assertReadFails((HEADER + "1: a\n# c\n2 x\n").replace("\n", "\r"), "Expected", 4);
        }

        @Test
        void invalidUtf8() throws IOException {
            Path f = dir.resolve("latin1.txt");
            Files.write(f, (HEADER + "1: Gr\u00fc\u00dfe\n").getBytes(StandardCharsets.ISO_8859_1));
            IOException e = assertThrows(IOException.class, () -> TxtFormat.read(f));
            assertTrue(e.getMessage().startsWith("Not a UTF-8 text file ("), e.getMessage());
            assertTrue(e.getMessage().endsWith("): " + f), e.getMessage());
        }

        @Test
        void missingFile() {
            assertThrows(java.nio.file.NoSuchFileException.class, () -> TxtFormat.read(dir.resolve("missing.txt")));
        }
    }
}
