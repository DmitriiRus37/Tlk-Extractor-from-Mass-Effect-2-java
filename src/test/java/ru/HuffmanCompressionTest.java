package ru;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuffmanCompressionTest {

    private static final String CYRILLIC = "Привет, мир!";
    private static final String EMOJI = "\uD83D\uDE00 smile";

    @TempDir
    Path dir;

    private int fileCounter;

    // ---------------------------------------------------------------- helpers

    private static String entry(int id, int position, String data) {
        return "<string><id>" + id + "</id><position>" + position + "</position><data>" + data + "</data></string>";
    }

    /** An XML document in the export format; {@code version == null} leaves out the attribute. */
    private Path xml(String version, String... strings) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tlkFile");
        if (version != null) {
            sb.append(" TLKToolVersion=\"").append(version).append('"');
        }
        sb.append(">\n");
        for (String s : strings) {
            sb.append("    ").append(s).append('\n');
        }
        sb.append("</tlkFile>\n");
        return TestSupport.writeUtf8(dir.resolve("in" + (fileCounter++) + ".xml"), sb.toString());
    }

    private static HuffmanCompression load(Path xml) throws Exception {
        return load(xml, false);
    }

    private static HuffmanCompression load(Path xml, boolean debug) throws Exception {
        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(xml.toString(), FileFormat.XML, debug);
        return hc;
    }

    private static Path small() {
        return TestSupport.resource("/xml/small.xml");
    }

    private static List<String> data(HuffmanCompression hc) {
        List<String> result = new ArrayList<>();
        for (HuffmanAccess.Entry e : HuffmanAccess.entries(hc)) {
            result.add(e.data);
        }
        return result;
    }

    /** Builds the oracle TLK for {@code hc} and loads it back with {@link TlkFile}. */
    private TlkFile roundTrip(HuffmanCompression hc) throws Exception {
        Path tlk = HuffmanAccess.oracleTlk(hc).writeTo(dir.resolve("oracle" + (fileCounter++) + ".tlk"));
        return loadTlk(tlk);
    }

    /** Writes {@code hc} with {@code saveToTlkFile} and loads the result with {@link TlkFile}. */
    private TlkFile save(HuffmanCompression hc) throws Exception {
        Path tlk = dir.resolve("saved" + (fileCounter++) + ".tlk");
        hc.saveToTlkFile(tlk.toString(), null);
        return loadTlk(tlk);
    }

    private static TlkFile loadTlk(Path tlk) throws IOException {
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        return t;
    }

    /** Asserts that loading fails with an IOException whose message contains {@code text}. */
    private static IOException assertLoadFails(Path xml, String text) {
        IOException e = assertThrows(IOException.class, () -> load(xml));
        assertTrue(e.getMessage().contains(text), e.getMessage());
        return e;
    }

    private static List<String> texts(TlkFile t) {
        return TlkFileTest.texts(t);
    }

    // ---------------------------------------------------------------- loading the XML

    @Nested
    class Loading {

        @Test
        void readsEveryStringElement() throws Exception {
            HuffmanCompression hc = load(small());
            List<HuffmanAccess.Entry> entries = HuffmanAccess.entries(hc);
            assertEquals(8, entries.size());
            int[] ids = {100, 101, -7, 134217731, 102, 103, 100, 134217731};
            for (int i = 0; i < entries.size(); i++) {
                assertEquals(ids[i], entries.get(i).id);
                assertEquals(i, entries.get(i).position);
            }
            assertEquals("1.0.4", HuffmanAccess.version(hc));
        }

        @Test
        void keepsTheTextAndMarksEntriesWithoutText() throws Exception {
            assertEquals(Arrays.asList(
                    "Hello",
                    CYRILLIC,
                    null,
                    "Male",
                    "",
                    "Line one\nLine two\t(tab)",
                    "Hello, <Shepard> & co.",
                    "Female"), data(load(small())));
        }

        @Test
        void sortsEntriesByPosition() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    entry(30, 3, "d"), entry(10, 1, "b"), entry(40, 0, "a"), entry(20, 2, "c")));
            assertEquals(Arrays.asList("a", "b", "c", "d"), data(hc));
            assertEquals(40, HuffmanAccess.entries(hc).get(0).id);
        }

        @Test
        void missingChildElementsDefaultToZeroAndEmptyText() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", "<string><data>x</data></string>", "<string/>"));
            for (HuffmanAccess.Entry e : HuffmanAccess.entries(hc)) {
                assertEquals(0, e.id);
                assertEquals(0, e.position);
            }
            assertEquals(new HashSet<>(Arrays.asList("x", "")), new HashSet<>(data(hc)));
        }

        @Test
        void ignoresUnknownElements() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    "<string><id>1</id><comment>ignored</comment><position>0</position><data>x</data></string>",
                    "<other><id>2</id></other>"));
            assertEquals(Collections.singletonList("x"), data(hc));
        }

        @Test
        void replacesCrLfButKeepsLoneCrAndLf() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "a&#13;&#10;b&#13;c&#10;d")));
            assertEquals(Collections.singletonList("a\nb\rc\nd"), data(hc));
        }

        @Test
        void unicodeText() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, CYRILLIC), entry(2, 1, EMOJI), entry(3, 2, "中文 &#xFFFD;")));
            assertEquals(Arrays.asList(CYRILLIC, EMOJI, "中文 \ufffd"), data(hc));
        }

        @Test
        void debugVersionPrefixesLocalizableIds() throws Exception {
            HuffmanCompression hc = load(small(), true);
            List<String> data = data(hc);
            assertEquals("(#100) Hello", data.get(0));
            assertEquals("(#101) " + CYRILLIC, data.get(1));
            assertNull(data.get(2));                         // negative id: no text
            assertEquals("Male", data.get(3));               // 0x08000003: bit 27 set, not localizable
            assertEquals("(#102) ", data.get(4));            // empty text is prefixed too
            assertEquals("Female", data.get(7));
        }

        @Test
        void debugPrefixForIdZero() throws Exception {
            assertEquals(Collections.singletonList("(#0) zero"), data(load(xml("1.0.4", entry(0, 0, "zero")), true)));
        }

        @Test
        void loadingAgainReplacesTheEntries() throws Exception {
            HuffmanCompression hc = load(small());
            hc.loadInputData(xml("1.0.4", entry(1, 0, "ab")).toString(), FileFormat.XML, false);
            assertEquals(Collections.singletonList("ab"), data(hc));
        }

        @Test
        void duplicateIdsAreAllKept() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(5, 0, "he"), entry(5, 1, "she")));
            assertEquals(Arrays.asList("he", "she"), data(hc));
            assertEquals(Integer.valueOf(2), HuffmanAccess.frequencies(hc).get('h'));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.0.3", "1.0.4", "1.0.3.0", "1.1", "2", "10.0.0"})
        void acceptsToolVersionsFrom103(String version) throws Exception {
            assertEquals(version, HuffmanAccess.version(load(xml(version, entry(1, 0, "x")))));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.0.2", "1.0", "1", "0.9.9", "1.0.2.9", "0"})
        void rejectsToolVersionsBefore103(String version) throws IOException {
            assertLoadFails(xml(version, entry(1, 0, "x")), "version " + version + ";");
        }

        @Test
        void rejectsOldVersionResource() {
            assertLoadFails(TestSupport.resource("/xml/old-version.xml"), "before 1.0.3");
        }

        @Test
        void rejectsNonNumericVersion() throws IOException {
            IOException e = assertLoadFails(xml("1.0.beta", entry(1, 0, "x")), "Invalid TLKToolVersion \"1.0.beta\"");
            assertInstanceOf(NumberFormatException.class, e.getCause());
        }

        @Test
        void rejectsNonNumericIdAndPosition() throws IOException {
            assertLoadFails(xml("1.0.4", "<string><id>abc</id><position>0</position><data>x</data></string>"),
                    "Invalid <id> \"abc\"");
            assertLoadFails(xml("1.0.4", "<string><id>1</id><position></position><data>x</data></string>"),
                    "Invalid <position> \"\"");
        }

        @Test
        void idAndPositionMayBeSurroundedByWhitespace() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", "<string><id> 7 </id><position>\n3\n</position><data>x</data></string>"));
            assertEquals(7, HuffmanAccess.entries(hc).get(0).id);
            assertEquals(3, HuffmanAccess.entries(hc).get(0).position);
        }

        @Test
        void rejectsMalformedXml() throws IOException {
            Path file = TestSupport.writeUtf8(dir.resolve("broken.xml"), "<tlkFile TLKToolVersion=\"1.0.4\"><string>");
            IOException e = assertLoadFails(file, "Malformed XML in " + file);
            assertInstanceOf(XMLStreamException.class, e.getCause());
        }

        @Test
        void rejectsAnotherRootElement() throws IOException {
            Path file = TestSupport.writeUtf8(dir.resolve("other.xml"), "<strings TLKToolVersion=\"1.0.4\"/>");
            assertLoadFails(file, "root element <strings>");
        }

        @Test
        void doesNotResolveExternalEntities() throws IOException {
            Path secret = TestSupport.writeUtf8(dir.resolve("secret.txt"), "secret");
            Path file = TestSupport.writeUtf8(dir.resolve("xxe.xml"), "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE tlkFile [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>\n"
                    + "<tlkFile TLKToolVersion=\"1.0.4\">" + entry(1, 0, "&x;") + "</tlkFile>");
            assertThrows(IOException.class, () -> load(file));
        }

        @Test
        void theFormatSelectsTheReader() {
            HuffmanCompression hc = new HuffmanCompression();
            IOException txt = assertThrows(IOException.class, () -> hc.loadInputData(small().toString(), FileFormat.TXT, false));
            assertTrue(txt.getMessage().startsWith("Missing header \"# TLKToolVersion=<version>\""), txt.getMessage());
            IOException csv = assertThrows(IOException.class, () -> hc.loadInputData(small().toString(), FileFormat.CSV, false));
            assertTrue(csv.getMessage().startsWith("Missing version record \"TLKToolVersion,<version>\""), csv.getMessage());
            assertThrows(NullPointerException.class, () -> hc.loadInputData(small().toString(), null, false));
        }

        @Test
        void hasTextAttributeOverridesTheSignOfTheId() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    "<string><id>5</id><position>0</position><data hasText=\"false\">-1</data></string>",
                    "<string><id>-6</id><position>1</position><data hasText=\"true\">negative</data></string>",
                    "<string><id>7</id><position>2</position><data hasText=\"true\">-1</data></string>",
                    "<string><id>-8</id><position>3</position><data hasText=\"false\">-1</data></string>"));
            assertEquals(Arrays.asList(null, "negative", "-1", null), data(hc));
        }

        @Test
        void rejectsAnInvalidHasTextAttribute() throws IOException {
            assertLoadFails(xml("1.0.4", "<string><id>5</id><position>0</position><data hasText=\"no\">x</data></string>"),
                    "hasText=\"no\"");
        }

        @Test
        void entryWithoutTextMustHoldANegativeNumber() throws Exception {
            assertLoadFails(xml("1.0.4", entry(1, 0, "a"), entry(-2, 1, "not a number")), "Invalid <data> \"not a number\"");
            assertLoadFails(xml("1.0.4", entry(-2, 0, "5")), "must be -1");
            assertEquals(Collections.singletonList(null), data(load(xml("1.0.4", entry(-2, 0, " -3 ")))));
        }

        @Test
        void failedLoadLeavesTheInstanceEmpty() throws Exception {
            HuffmanCompression hc = load(small());
            Path bad = xml("1.0.4", entry(1, 0, "a"), entry(2, 1, "b"), "<string><id>x</id></string>");
            assertThrows(IOException.class, () -> hc.loadInputData(bad.toString(), FileFormat.XML, false));
            assertTrue(HuffmanAccess.entries(hc).isEmpty());
            assertTrue(HuffmanAccess.codes(hc).isEmpty());
            assertTrue(HuffmanAccess.frequencies(hc).isEmpty());
            assertEquals("1.0.0.0", HuffmanAccess.version(hc));
        }

        @Test
        void missingFile() {
            assertThrows(IOException.class, () -> load(dir.resolve("missing.xml")));
        }

        @Test
        void missingToolVersionIsRejectedAsTooOld() {
            assertLoadFails(TestSupport.resource("/xml/no-version.xml"), "version 1.0.0.0 (it has no TLKToolVersion attribute)");
        }

        @Test
        void emptyStringListCanBeLoaded() throws Exception {
            HuffmanCompression hc = new HuffmanCompression();
            assertDoesNotThrow(() -> hc.loadInputData(TestSupport.resource("/xml/empty.xml").toString(), FileFormat.XML, false));
            assertTrue(HuffmanAccess.entries(hc).isEmpty());
        }

        @Test
        void onlyEntriesWithoutTextCanBeLoaded() throws Exception {
            Path file = xml("1.0.4", entry(-1, 0, "-1"), entry(-2, 1, "-1"));
            HuffmanCompression hc = new HuffmanCompression();
            assertDoesNotThrow(() -> hc.loadInputData(file.toString(), FileFormat.XML, false));
            assertEquals(2, HuffmanAccess.entries(hc).size());
            assertTrue(HuffmanAccess.codes(hc).isEmpty());
            assertTrue(HuffmanAccess.treeBuffer(hc).isEmpty());
        }

        @Test
        void thirdOccurrenceOfAnIdIsRejected() throws IOException {
            Path in = xml("1.0.4", entry(5, 0, "one"), entry(6, 1, "x"), entry(5, 2, "two"), entry(5, 3, "three"));
            assertLoadFails(in, "String id 5 occurs 3 times (positions [0, 2, 3])");
        }
    }

    // ---------------------------------------------------------------- Huffman code

    @Nested
    class Coding {

        @Test
        void countsEveryCharacterOfTextEntriesIncludingTerminators() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "abb"), entry(2, 1, "b"), entry(-3, 2, "-1")));
            Map<Character, Integer> expected = new HashMap<>();
            expected.put('a', 1);
            expected.put('b', 3);
            expected.put('\0', 2);
            assertEquals(expected, HuffmanAccess.frequencies(hc));
        }

        @Test
        void identicalTextsAreCountedOnce() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "ab"), entry(2, 1, "ab"), entry(1, 2, "ab")));
            Map<Character, Integer> expected = new HashMap<>();
            expected.put('a', 1);
            expected.put('b', 1);
            expected.put('\0', 1);
            assertEquals(expected, HuffmanAccess.frequencies(hc));
        }

        @Test
        void treeIsDeterministic() throws Exception {
            Path xml = xml("1.0.4", entry(1, 0, "abcdefgh abcd ab"), entry(2, 1, EMOJI + CYRILLIC));
            List<Integer> tree = HuffmanAccess.treeBuffer(load(xml));
            for (int i = 0; i < 5; i++) {
                assertEquals(tree, HuffmanAccess.treeBuffer(load(xml)));
            }
        }

        @Test
        void everyCountedCharacterGetsACode() throws Exception {
            HuffmanCompression hc = load(small());
            assertEquals(HuffmanAccess.frequencies(hc).keySet(), HuffmanAccess.codes(hc).keySet());
            assertTrue(HuffmanAccess.codes(hc).containsKey('\0'));
            assertTrue(HuffmanAccess.codes(hc).containsKey('П'));
            // characters only found in entries without text are not coded
            assertFalse(HuffmanAccess.codes(hc).containsKey('-'));
        }

        @Test
        void codesArePrefixFreeAndComplete() throws Exception {
            for (Path xml : Arrays.asList(small(), xml("1.0.4", entry(1, 0, "aaaaaaaabbbbccd"), entry(2, 1, EMOJI)))) {
                HuffmanCompression hc = load(xml);
                Map<Character, BitArray> codes = HuffmanAccess.codes(hc);
                HuffmanAccess.assertPrefixFree(codes);
                assertEquals(1.0, HuffmanAccess.kraftSum(codes), 1e-12);
            }
        }

        @Test
        void codeIsOptimal() throws Exception {
            HuffmanCompression hc = load(small());
            Map<Character, Integer> freq = HuffmanAccess.frequencies(hc);
            assertEquals(HuffmanAccess.optimalBits(freq), HuffmanAccess.encodedBits(freq, HuffmanAccess.codes(hc)));
        }

        @Test
        void moreFrequentCharactersGetCodesNoLonger() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "aaaaaaaaaaaaaaaabbbbbbbbccccdde")));
            Map<Character, Integer> freq = HuffmanAccess.frequencies(hc);
            Map<Character, BitArray> codes = HuffmanAccess.codes(hc);
            for (char x : freq.keySet()) {
                for (char y : freq.keySet()) {
                    if (freq.get(x) > freq.get(y)) {
                        assertTrue(codes.get(x).length() <= codes.get(y).length(), x + " vs " + y);
                    }
                }
            }
            assertEquals(1, codes.get('a').length());
        }

        @Test
        void twoSymbolsGetOneBitEach() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "aaa")));
            Map<Character, BitArray> codes = HuffmanAccess.codes(hc);
            assertEquals(2, codes.size());
            assertEquals(1, codes.get('a').length());
            assertEquals(1, codes.get('\0').length());
            assertNotEquals(codes.get('a'), codes.get('\0'));
        }

        @Test
        void treeBufferEncodesTheSameTreeAsTheCodes() throws Exception {
            HuffmanCompression hc = load(small());
            List<Integer> tree = HuffmanAccess.treeBuffer(hc);
            assertEquals(2 * (HuffmanAccess.codes(hc).size() - 1), tree.size());
            HuffmanAccess.assertTreeDecodesCodes(tree, HuffmanAccess.codes(hc));
            // node 0 is the root: no node points back to it
            for (int child : tree) {
                assertNotEquals(0, child);
            }
            // calling it again yields the same buffer
            assertEquals(tree, HuffmanAccess.treeBuffer(hc));
        }

        @Test
        void singleDistinctCharacterGetsANonEmptyCode() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, ""), entry(2, 1, "")));
            assertEquals(1, HuffmanAccess.codes(hc).size());
            assertTrue(HuffmanAccess.codes(hc).get('\0').length() >= 1, "empty code for '\\0'");
            assertEquals(Arrays.asList(-1, -1), HuffmanAccess.treeBuffer(hc));
            assertEquals(Arrays.asList("", ""), texts(roundTrip(hc)));
            assertEquals(Arrays.asList("", ""), texts(save(hc)));
        }

        @Test
        void reusingAnInstanceDoesNotMixTrees() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "abcdef")));
            hc.loadInputData(xml("1.0.4", entry(1, 0, "xx")).toString(), FileFormat.XML, false);
            assertEquals(new HashSet<>(Arrays.asList('x', '\0')), HuffmanAccess.codes(hc).keySet());
            assertEquals(2, HuffmanAccess.treeBuffer(hc).size());
        }
    }

    // ---------------------------------------------------------------- bit packing

    @Nested
    class Packing {

        private BitArray bits(String zeroesAndOnes) {
            boolean[] b = new boolean[zeroesAndOnes.length()];
            for (int i = 0; i < b.length; i++) {
                b[i] = zeroesAndOnes.charAt(i) == '1';
            }
            return new BitArray(b);
        }

        @Test
        void packsLeastSignificantBitFirst() {
            // stream 1 0 1 1 1 1 1 1 | 1 -> 0b11111101, 0b00000001
            assertArrayEquals(new byte[]{(byte) 0xfd, 0x01},
                    HuffmanAccess.pack(Arrays.asList(bits("101"), bits("111111"))));
        }

        @Test
        void exactByteBoundaries() {
            assertArrayEquals(new byte[]{0x01}, HuffmanAccess.pack(Collections.singletonList(bits("10000000"))));
            assertArrayEquals(new byte[]{(byte) 0x80}, HuffmanAccess.pack(Collections.singletonList(bits("00000001"))));
            assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xff},
                    HuffmanAccess.pack(Arrays.asList(bits("1111"), bits("111111111111"))));
        }

        @Test
        void codesSpanningBytesAndPadding() {
            // 3 + 3 + 3 bits: 110 011 101 -> bits 0..8 = 1,1,0,0,1,1,1,0,1
            assertArrayEquals(new byte[]{0x73, 0x01},
                    HuffmanAccess.pack(Arrays.asList(bits("110"), bits("011"), bits("101"))));
        }

        @Test
        void emptyInput() {
            assertArrayEquals(new byte[0], HuffmanAccess.pack(Collections.<BitArray>emptyList()));
            assertArrayEquals(new byte[0], HuffmanAccess.pack(Collections.singletonList(new BitArray(0))));
        }

        @Test
        void zeroLengthCodesAreSkipped() {
            assertArrayEquals(new byte[]{0x05},
                    HuffmanAccess.pack(Arrays.asList(bits("1"), new BitArray(0), bits("01"))));
        }

        @Test
        void growsBeyondItsInitialBuffer() {
            List<BitArray> stream = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                stream.add(bits(i % 3 == 0 ? "1" : "0000001"));
            }
            byte[] packed = HuffmanAccess.pack(stream);
            int ones = 0;
            for (byte b : packed) {
                ones += Integer.bitCount(b & 0xff);
            }
            assertEquals(3000, ones);
            assertEquals((1000 + 2000 * 7 + 7) / 8, packed.length);
        }

        @Test
        void packedAppendMatchesBitwiseAppend() {
            java.util.Random random = new java.util.Random(42);
            HuffmanCompression.BitWriter bitwise = new HuffmanCompression.BitWriter();
            HuffmanCompression.BitWriter packed = new HuffmanCompression.BitWriter();
            for (int i = 0; i < 20000; i++) {
                int length = random.nextInt(HuffmanCompression.BitWriter.MAX_PACKED_BITS + 1);
                long code = length == 0 ? 0 : random.nextLong() >>> (Long.SIZE - length);
                boolean[] b = new boolean[length];
                for (int k = 0; k < length; k++) {
                    b[k] = (code >>> k & 1) != 0;
                }
                bitwise.append(new BitArray(b));
                packed.append(code, length);
                assertEquals(bitwise.bitCount(), packed.bitCount());
            }
            assertArrayEquals(bitwise.toByteArray(), packed.toByteArray());
        }

        @Test
        void copyIntoJoinsStreamsAtAnyBitOffset() {
            java.util.Random random = new java.util.Random(5);
            for (int round = 0; round < 500; round++) {
                HuffmanCompression.BitWriter whole = new HuffmanCompression.BitWriter();
                List<HuffmanCompression.BitWriter> parts = new ArrayList<>();
                for (int p = random.nextInt(5); p >= 0; p--) {
                    HuffmanCompression.BitWriter part = new HuffmanCompression.BitWriter();
                    for (int c = random.nextInt(6); c > 0; c--) {
                        int length = 1 + random.nextInt(20);
                        long code = random.nextLong() >>> (Long.SIZE - length);
                        part.append(code, length);
                        whole.append(code, length);
                    }
                    parts.add(part);
                }
                byte[] joined = new byte[whole.toByteArray().length];
                int offset = 0;
                for (HuffmanCompression.BitWriter part : parts) {
                    part.copyInto(joined, offset);
                    offset += part.bitCount();
                }
                assertArrayEquals(whole.toByteArray(), joined);
            }
        }
    }

    // ---------------------------------------------------------------- version comparison

    @Nested
    class VersionComparison {

        @Test
        void lessThan() {
            assertTrue(HuffmanAccess.compareVersions("1.0.2", "1.0.3", "<"));
            assertTrue(HuffmanAccess.compareVersions("1.0", "1.0.3", "<"));
            assertTrue(HuffmanAccess.compareVersions("0.99", "1", "<"));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.3", "<"));
            assertFalse(HuffmanAccess.compareVersions("1.0.10", "1.0.9", "<"));
            assertFalse(HuffmanAccess.compareVersions("2", "1.9.9", "<"));
        }

        @Test
        void greaterThan() {
            assertTrue(HuffmanAccess.compareVersions("1.0.10", "1.0.9", ">"));
            assertTrue(HuffmanAccess.compareVersions("1.1", "1.0.9", ">"));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.3.0", ">"));
            assertFalse(HuffmanAccess.compareVersions("1.0.2", "1.0.3", ">"));
        }

        @Test
        void equalTreatsMissingComponentsAsZero() {
            assertTrue(HuffmanAccess.compareVersions("1.0.3", "1.0.3.0", "="));
            assertTrue(HuffmanAccess.compareVersions("1", "1.0.0", "="));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.4", "="));
            assertFalse(HuffmanAccess.compareVersions("1.0.4", "1.0.3", "="));
        }

        @Test
        void nonNumericComponentIsRejected() {
            assertThrows(NumberFormatException.class, () -> HuffmanAccess.compareVersions("1.x", "1.0", "<"));
        }
    }

    // ---------------------------------------------------------------- round trip and TLK output

    @Nested
    class RoundTrip {

        @Test
        void smallXmlDecodesBackThroughTlkFile() throws Exception {
            TlkFile t = roundTrip(load(small()));
            assertEquals(6, TlkFileTest.header(t).entry1Count);
            assertEquals(2, TlkFileTest.header(t).entry2Count);
            assertEquals(Arrays.asList("Hello", CYRILLIC, null, "Male", "", "Line one\nLine two\t(tab)",
                    "Hello, <Shepard> & co.", "Female"), texts(t));
            List<TlkFile.TlkStringRef> refs = TlkFileTest.refs(t);
            assertEquals(-7, refs.get(2).stringId);
            assertEquals(-1, refs.get(2).bitOffset);
            assertEquals(100, refs.get(6).stringId);
        }

        @Test
        void debugTextDecodesWithPrefix() throws Exception {
            List<String> texts = texts(roundTrip(load(small(), true)));
            assertEquals("(#100) Hello", texts.get(0));
            assertEquals("Male", texts.get(3));
            assertEquals("(#102) ", texts.get(4));
        }

        @Test
        void unicodeAndEmptyStrings() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    entry(1, 0, CYRILLIC), entry(2, 1, ""), entry(3, 2, EMOJI), entry(4, 3, "中文 &#xFFFD;&#x10FFFF;"), entry(5, 4, "")));
            assertEquals(Arrays.asList(CYRILLIC, "", EMOJI, "中文 \ufffd\udbff\udfff", ""), texts(roundTrip(hc)));
        }

        @Test
        void largeAlphabet() throws Exception {
            StringBuilder sb = new StringBuilder();
            for (char c = 0x20; c < 0x500; c++) {
                if (c != '<' && c != '&') {
                    for (int k = 0; k <= c % 5; k++) {
                        sb.append(c);
                    }
                }
            }
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, sb.toString()), entry(2, 1, "tail")));
            HuffmanAccess.assertPrefixFree(HuffmanAccess.codes(hc));
            HuffmanAccess.assertTreeDecodesCodes(HuffmanAccess.treeBuffer(hc), HuffmanAccess.codes(hc));
            assertEquals(Arrays.asList(sb.toString(), "tail"), texts(roundTrip(hc)));
        }
    }

    @Nested
    class SaveToTlkFile {

        @Test
        void writesAFileEndingWithTheEncodedData() throws Exception {
            HuffmanCompression hc = load(small());
            Path out = dir.resolve("out.tlk");
            hc.saveToTlkFile(out.toString(), null);
            byte[] written = Files.readAllBytes(out);
            byte[] data = HuffmanAccess.expectedDataBlock(hc);
            assertTrue(written.length > data.length);
            assertArrayEquals(data, Arrays.copyOfRange(written, written.length - data.length, written.length));
            assertEquals('T', written[0]);
        }

        @Test
        void writesTheLittleEndianLayout() throws Exception {
            HuffmanCompression hc = load(small());
            Path out = dir.resolve("layout.tlk");
            hc.saveToTlkFile(out.toString(), null);
            byte[] written = Files.readAllBytes(out);
            List<Integer> tree = HuffmanAccess.treeBuffer(hc);
            byte[] data = HuffmanAccess.expectedDataBlock(hc);
            // header, 6 male and 2 female refs, the tree, the data
            assertArrayEquals(TlkTestFile.intsLE(TlkTestFile.MAGIC, 3, 2, 6, 2, tree.size() / 2, data.length),
                    Arrays.copyOf(written, TlkTestFile.HEADER_SIZE));
            assertEquals(TlkTestFile.HEADER_SIZE + 8 * 8 + 4 * tree.size() + data.length, written.length);
            int treeOffset = TlkTestFile.HEADER_SIZE + 8 * 8;
            for (int i = 0; i < tree.size(); i++) {
                assertArrayEquals(TlkTestFile.intsLE(tree.get(i)),
                        Arrays.copyOfRange(written, treeOffset + 4 * i, treeOffset + 4 * i + 4), "tree int " + i);
            }
            // the ref without text (id -7, position 2) has offset -1
            assertArrayEquals(TlkTestFile.intsLE(-7, -1),
                    Arrays.copyOfRange(written, TlkTestFile.HEADER_SIZE + 16, TlkTestFile.HEADER_SIZE + 24));
        }

        @Test
        void replacesAnExistingFile() throws Exception {
            Path out = dir.resolve("existing.tlk");
            byte[] junk = new byte[100_000];
            Arrays.fill(junk, (byte) 'x');
            Files.write(out, junk);
            load(small()).saveToTlkFile(out.toString(), null);
            assertTrue(Files.size(out) < junk.length);
            assertNotEquals('x', Files.readAllBytes(out)[0]);
        }

        @Test
        void entryWithoutTextInBothSections() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(-5, 0, "-1"), entry(1, 1, "a"), entry(-5, 2, "-1")));
            for (TlkFile t : Arrays.asList(roundTrip(hc), save(hc))) {
                assertEquals(2, TlkFileTest.header(t).entry1Count);
                assertEquals(1, TlkFileTest.header(t).entry2Count);
                assertEquals(Arrays.asList(null, "a", null), texts(t));
                assertEquals(-1, TlkFileTest.refs(t).get(2).bitOffset);
            }
        }

        @Test
        void unwritableTargetFails() throws Exception {
            HuffmanCompression hc = load(small());
            String out = dir.resolve("missing-dir").resolve("out.tlk").toString();
            assertThrows(IOException.class, () -> hc.saveToTlkFile(out, null));
        }

        @Test
        void savedTlkLoadsBack() throws Exception {
            TlkFile t = save(load(small()));
            assertEquals(6, TlkFileTest.header(t).entry1Count);
            assertEquals(2, TlkFileTest.header(t).entry2Count);
            assertEquals(Arrays.asList("Hello", CYRILLIC, null, "Male", "", "Line one\nLine two\t(tab)",
                    "Hello, <Shepard> & co.", "Female"), texts(t));
            int[] ids = {100, 101, -7, 134217731, 102, 103, 100, 134217731};
            for (int i = 0; i < ids.length; i++) {
                assertEquals(ids[i], TlkFileTest.refs(t).get(i).stringId);
            }
        }

        @Test
        void savedTlkKeepsThePositionOrder() throws Exception {
            int[] ids = {1000, 3, 77, 500000, 42, 9, 65536, 12};
            List<String> strings = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                strings.add(entry(ids[i], ids.length - 1 - i, "s" + i));
            }
            List<TlkFile.TlkStringRef> refs = TlkFileTest.refs(save(load(xml("1.0.4", strings.toArray(new String[0])))));
            for (int i = 0; i < ids.length; i++) {
                int expected = ids.length - 1 - i;
                assertEquals(ids[expected], refs.get(i).stringId, "position " + i);
                assertEquals("s" + expected, refs.get(i).data);
            }
        }

        @Test
        void secondOccurrencesFormTheFemaleSectionInPositionOrder() throws Exception {
            TlkFile t = save(load(xml("1.0.4", entry(1, 0, "a"), entry(2, 1, "b"), entry(2, 2, "B"),
                    entry(3, 3, "c"), entry(1, 4, "A"))));
            assertEquals(3, TlkFileTest.header(t).entry1Count);
            assertEquals(2, TlkFileTest.header(t).entry2Count);
            assertEquals(Arrays.asList("a", "b", "c", "B", "A"), texts(t));
        }

        @Test
        void identicalTextsShareOneBitOffset() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "same"), entry(2, 1, "other"),
                    entry(3, 2, "same"), entry(1, 3, "same")));
            TlkFile t = save(hc);
            List<TlkFile.TlkStringRef> refs = TlkFileTest.refs(t);
            assertEquals(refs.get(0).bitOffset, refs.get(2).bitOffset);
            assertEquals(refs.get(0).bitOffset, refs.get(3).bitOffset);
            assertNotEquals(refs.get(0).bitOffset, refs.get(1).bitOffset);
            assertEquals(Arrays.asList("same", "other", "same", "same"), texts(t));
            assertEquals(HuffmanAccess.expectedDataBlock(hc).length, TlkFileTest.header(t).dataLen);
        }

        @Test
        void emptyInputGivesAnEmptyTlk() throws Exception {
            TlkFile t = save(load(TestSupport.resource("/xml/empty.xml")));
            assertEquals(0, TlkFileTest.header(t).entry1Count);
            assertEquals(0, TlkFileTest.header(t).treeNodeCount);
            assertEquals(0, TlkFileTest.header(t).dataLen);
            assertTrue(TlkFileTest.refs(t).isEmpty());
        }

        @Test
        void onlyEntriesWithoutTextGiveATlkWithoutTree() throws Exception {
            TlkFile t = save(load(xml("1.0.4", entry(-1, 0, "-1"), entry(-2, 1, "-1"))));
            assertEquals(2, TlkFileTest.header(t).entry1Count);
            assertEquals(0, TlkFileTest.header(t).treeNodeCount);
            assertEquals(Arrays.asList(null, null), texts(t));
        }

        @Test
        void reportsProgressUpToOne() throws Exception {
            final List<Double> reported = new ArrayList<>();
            load(small()).saveToTlkFile(dir.resolve("progress.tlk").toString(), reported::add);
            assertFalse(reported.isEmpty());
            assertEquals(1.0, reported.get(reported.size() - 1), 0.0);
            for (int i = 1; i < reported.size(); i++) {
                assertTrue(reported.get(i) > reported.get(i - 1), reported.toString());
            }
        }

        @Test
        void savingWithoutLoadingWritesAnEmptyTlk() throws Exception {
            TlkFile t = save(new HuffmanCompression());
            assertTrue(TlkFileTest.refs(t).isEmpty());
        }

        @Test
        void entriesThatBreakTheIdSignRuleSurviveTlkToXmlToTlk() throws Exception {
            // ids >= 0 normally have text and negative ids none; the hasText attribute keeps the others
            Path tlk = new TlkTestFile().male(1, "-1").maleRaw(2, -1).male(-3, "negative").maleRaw(-4, -1)
                    .writeTo(dir.resolve("signs.tlk"));
            Path xml = dir.resolve("signs.xml");
            loadTlk(tlk).storeToFile(xml.toString(), FileFormat.XML, null);
            TlkFile t = save(load(xml));
            assertEquals(Arrays.asList("-1", null, "negative", null), texts(t));
            int[] ids = {1, 2, -3, -4};
            for (int i = 0; i < ids.length; i++) {
                assertEquals(ids[i], TlkFileTest.refs(t).get(i).stringId);
            }
        }
    }

    @Test
    void freshInstanceIsEmpty() {
        HuffmanCompression hc = new HuffmanCompression();
        assertTrue(HuffmanAccess.entries(hc).isEmpty());
        assertTrue(HuffmanAccess.codes(hc).isEmpty());
        assertEquals("1.0.0.0", HuffmanAccess.version(hc));
    }
}
