package ru;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        return t;
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
        void terminatesTextAndKeepsOffsetOfEntriesWithoutText() throws Exception {
            assertEquals(Arrays.asList(
                    "Hello\0",
                    CYRILLIC + "\0",
                    "-1",
                    "Male\0",
                    "\0",
                    "Line one\nLine two\t(tab)\0",
                    "Hello, <Shepard> & co.\0",
                    "Female\0"), data(load(small())));
        }

        @Test
        void sortsEntriesByPosition() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    entry(30, 3, "d"), entry(10, 1, "b"), entry(40, 0, "a"), entry(20, 2, "c")));
            assertEquals(Arrays.asList("a\0", "b\0", "c\0", "d\0"), data(hc));
            assertEquals(40, HuffmanAccess.entries(hc).get(0).id);
        }

        @Test
        void missingChildElementsDefaultToZeroAndEmptyText() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", "<string><data>x</data></string>", "<string/>"));
            for (HuffmanAccess.Entry e : HuffmanAccess.entries(hc)) {
                assertEquals(0, e.id);
                assertEquals(0, e.position);
            }
            assertEquals(new HashSet<>(Arrays.asList("x\0", "\0")), new HashSet<>(data(hc)));
        }

        @Test
        void ignoresUnknownElements() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4",
                    "<string><id>1</id><comment>ignored</comment><position>0</position><data>x</data></string>",
                    "<other><id>2</id></other>"));
            assertEquals(Collections.singletonList("x\0"), data(hc));
        }

        @Test
        void replacesCrLfButKeepsLoneCrAndLf() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "a&#13;&#10;b&#13;c&#10;d")));
            assertEquals(Collections.singletonList("a\nb\rc\nd\0"), data(hc));
        }

        @Test
        void unicodeText() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, CYRILLIC), entry(2, 1, EMOJI), entry(3, 2, "中文 &#xFFFD;")));
            assertEquals(Arrays.asList(CYRILLIC + "\0", EMOJI + "\0", "中文 \ufffd\0"), data(hc));
        }

        @Test
        void debugVersionPrefixesLocalizableIds() throws Exception {
            HuffmanCompression hc = load(small(), true);
            List<String> data = data(hc);
            assertEquals("(#100) Hello\0", data.get(0));
            assertEquals("(#101) " + CYRILLIC + "\0", data.get(1));
            assertEquals("-1", data.get(2));                 // negative id: no text
            assertEquals("Male\0", data.get(3));             // 0x08000003: bit 27 set, not localizable
            assertEquals("(#102) \0", data.get(4));          // empty text is prefixed too
            assertEquals("Female\0", data.get(7));
        }

        @Test
        void debugPrefixForIdZero() throws Exception {
            assertEquals(Collections.singletonList("(#0) zero\0"), data(load(xml("1.0.4", entry(0, 0, "zero")), true)));
        }

        @Test
        void loadingAgainReplacesTheEntries() throws Exception {
            HuffmanCompression hc = load(small());
            hc.loadInputData(xml("1.0.4", entry(1, 0, "ab")).toString(), FileFormat.XML, false);
            assertEquals(Collections.singletonList("ab\0"), data(hc));
        }

        @Test
        void duplicateIdsAreAllKept() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(5, 0, "he"), entry(5, 1, "she")));
            assertEquals(Arrays.asList("he\0", "she\0"), data(hc));
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
            Path file = xml(version, entry(1, 0, "x"));
            assertThrows(RuntimeException.class, () -> load(file));
        }

        @Test
        void rejectsOldVersionResource() {
            assertThrows(RuntimeException.class, () -> load(TestSupport.resource("/xml/old-version.xml")));
        }

        @Test
        void rejectsNonNumericVersion() throws IOException {
            Path file = xml("1.0.beta", entry(1, 0, "x"));
            assertThrows(NumberFormatException.class, () -> load(file));
        }

        @Test
        void rejectsNonNumericIdAndPosition() throws IOException {
            Path badId = xml("1.0.4", "<string><id>abc</id><position>0</position><data>x</data></string>");
            assertThrows(NumberFormatException.class, () -> load(badId));
            Path badPosition = xml("1.0.4", "<string><id>1</id><position></position><data>x</data></string>");
            assertThrows(NumberFormatException.class, () -> load(badPosition));
        }

        @Test
        void rejectsMalformedXml() throws IOException {
            Path file = TestSupport.writeUtf8(dir.resolve("broken.xml"), "<tlkFile TLKToolVersion=\"1.0.4\"><string>");
            assertThrows(SAXException.class, () -> load(file));
        }

        @Test
        void missingFile() {
            assertThrows(IOException.class, () -> load(dir.resolve("missing.xml")));
        }

        @Test
        @Disabled("Known bug: a missing TLKToolVersion attribute causes a NullPointerException "
                + "instead of being treated as the oldest version (1.0.0.0) and rejected")
        void missingToolVersionIsRejectedAsTooOld() {
            Exception e = assertThrows(Exception.class, () -> load(TestSupport.resource("/xml/no-version.xml")));
            assertFalse(e instanceof NullPointerException, "NullPointerException instead of a version error");
        }

        @Test
        @Disabled("Known bug: an XML without any string makes loadInputData throw IndexOutOfBoundsException")
        void emptyStringListCanBeLoaded() throws Exception {
            HuffmanCompression hc = new HuffmanCompression();
            assertDoesNotThrow(() -> hc.loadInputData(TestSupport.resource("/xml/empty.xml").toString(), FileFormat.XML, false));
            assertTrue(HuffmanAccess.entries(hc).isEmpty());
        }

        @Test
        @Disabled("Known bug: an XML whose strings all lack text (negative ids) makes loadInputData throw "
                + "IndexOutOfBoundsException")
        void onlyEntriesWithoutTextCanBeLoaded() throws Exception {
            Path file = xml("1.0.4", entry(-1, 0, "-1"), entry(-2, 1, "-1"));
            HuffmanCompression hc = new HuffmanCompression();
            assertDoesNotThrow(() -> hc.loadInputData(file.toString(), FileFormat.XML, false));
            assertEquals(2, HuffmanAccess.entries(hc).size());
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
        @Disabled("Bug: with a single distinct character (e.g. only empty strings) the code is empty and "
                + "the tree has no node, so nothing can be encoded or decoded")
        void singleDistinctCharacterGetsANonEmptyCode() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, ""), entry(2, 1, "")));
            assertEquals(1, HuffmanAccess.codes(hc).size());
            assertTrue(HuffmanAccess.codes(hc).get('\0').length() >= 1, "empty code for '\\0'");
            assertFalse(HuffmanAccess.treeBuffer(hc).isEmpty(), "tree without nodes");
            assertEquals(Arrays.asList("", ""), texts(roundTrip(hc)));
        }

        @Test
        @Disabled("Bug: loadInputData does not clear the previous Huffman tree and codes, so reusing an "
                + "instance mixes the old characters into the new code")
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
        void packsLeastSignificantBitFirst() throws Exception {
            // stream 1 0 1 1 1 1 1 1 | 1 -> 0b11111101, 0b00000001
            assertArrayEquals(new byte[]{(byte) 0xfd, 0x01},
                    HuffmanAccess.pack(Arrays.asList(bits("101"), bits("111111")), 9));
        }

        @Test
        void exactByteBoundaries() throws Exception {
            assertArrayEquals(new byte[]{0x01}, HuffmanAccess.pack(Collections.singletonList(bits("10000000")), 8));
            assertArrayEquals(new byte[]{(byte) 0x80}, HuffmanAccess.pack(Collections.singletonList(bits("00000001")), 8));
            assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xff},
                    HuffmanAccess.pack(Arrays.asList(bits("1111"), bits("111111111111")), 16));
        }

        @Test
        void codesSpanningBytesAndPadding() throws Exception {
            // 3 + 3 + 3 bits: 110 011 101 -> bits 0..8 = 1,1,0,0,1,1,1,0,1
            assertArrayEquals(new byte[]{0x73, 0x01},
                    HuffmanAccess.pack(Arrays.asList(bits("110"), bits("011"), bits("101")), 9));
        }

        @Test
        void emptyInput() throws Exception {
            assertArrayEquals(new byte[0], HuffmanAccess.pack(Collections.<BitArray>emptyList(), 0));
            assertArrayEquals(new byte[0], HuffmanAccess.pack(Collections.singletonList(new BitArray(0)), 0));
        }

        @Test
        void zeroLengthCodesAreSkipped() throws Exception {
            assertArrayEquals(new byte[]{0x05},
                    HuffmanAccess.pack(Arrays.asList(bits("1"), new BitArray(0), bits("01")), 3));
        }

        @Test
        void matchesTheIndependentPackingOfTheTestOracle() throws Exception {
            HuffmanCompression hc = load(small());
            List<BitArray> stream = new ArrayList<>();
            int count = 0;
            for (HuffmanAccess.Entry e : HuffmanAccess.entries(hc)) {
                if (e.id >= 0) {
                    for (char c : e.data.toCharArray()) {
                        BitArray code = HuffmanAccess.codes(hc).get(c);
                        stream.add(code);
                        count += code.length();
                    }
                }
            }
            assertArrayEquals(HuffmanAccess.expectedDataBlock(hc), HuffmanAccess.pack(stream, count));
        }
    }

    // ---------------------------------------------------------------- version comparison

    @Nested
    class VersionComparison {

        @Test
        void lessThan() throws Exception {
            assertTrue(HuffmanAccess.compareVersions("1.0.2", "1.0.3", "<"));
            assertTrue(HuffmanAccess.compareVersions("1.0", "1.0.3", "<"));
            assertTrue(HuffmanAccess.compareVersions("0.99", "1", "<"));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.3", "<"));
            assertFalse(HuffmanAccess.compareVersions("1.0.10", "1.0.9", "<"));
            assertFalse(HuffmanAccess.compareVersions("2", "1.9.9", "<"));
        }

        @Test
        void greaterThan() throws Exception {
            assertTrue(HuffmanAccess.compareVersions("1.0.10", "1.0.9", ">"));
            assertTrue(HuffmanAccess.compareVersions("1.1", "1.0.9", ">"));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.3.0", ">"));
            assertFalse(HuffmanAccess.compareVersions("1.0.2", "1.0.3", ">"));
        }

        @Test
        void equalTreatsMissingComponentsAsZero() throws Exception {
            assertTrue(HuffmanAccess.compareVersions("1.0.3", "1.0.3.0", "="));
            assertTrue(HuffmanAccess.compareVersions("1", "1.0.0", "="));
            assertFalse(HuffmanAccess.compareVersions("1.0.3", "1.0.4", "="));
            assertFalse(HuffmanAccess.compareVersions("1.0.4", "1.0.3", "="));
        }

        @Test
        void invalidSignIsRejected() {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> HuffmanAccess.compareVersions("1", "2", "<="));
            assertTrue(e.getMessage().contains("valid sign"));
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
            hc.saveToTlkFile(out.toString(), true);
            byte[] written = Files.readAllBytes(out);
            byte[] data = HuffmanAccess.expectedDataBlock(hc);
            assertTrue(written.length > data.length);
            assertArrayEquals(data, Arrays.copyOfRange(written, written.length - data.length, written.length));
            assertEquals('T', written[0]);
        }

        @Test
        void replacesAnExistingFile() throws Exception {
            Path out = dir.resolve("existing.tlk");
            byte[] junk = new byte[100_000];
            Arrays.fill(junk, (byte) 'x');
            Files.write(out, junk);
            load(small()).saveToTlkFile(out.toString(), true);
            assertTrue(Files.size(out) < junk.length);
            assertNotEquals('x', Files.readAllBytes(out)[0]);
        }

        @Test
        void entryWithoutTextInBothSections() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(-5, 0, "-1"), entry(1, 1, "a"), entry(-5, 2, "-1")));
            Path out = dir.resolve("negative.tlk");
            hc.saveToTlkFile(out.toString(), true);
            byte[] written = Files.readAllBytes(out);
            byte[] data = HuffmanAccess.expectedDataBlock(hc);
            assertArrayEquals(data, Arrays.copyOfRange(written, written.length - data.length, written.length));

            TlkFile t = roundTrip(hc);
            assertEquals(2, TlkFileTest.header(t).entry1Count);
            assertEquals(1, TlkFileTest.header(t).entry2Count);
            assertEquals(Arrays.asList(null, "a", null), texts(t));
        }

        @Test
        void entryWithoutTextMustHoldANumericOffset() throws Exception {
            HuffmanCompression hc = load(xml("1.0.4", entry(1, 0, "a"), entry(-2, 1, "not a number")));
            String out = dir.resolve("bad.tlk").toString();
            assertThrows(NumberFormatException.class, () -> hc.saveToTlkFile(out, true));
        }

        @Test
        void unwritableTargetFails() throws Exception {
            HuffmanCompression hc = load(small());
            String out = dir.resolve("missing-dir").resolve("out.tlk").toString();
            assertThrows(IOException.class, () -> hc.saveToTlkFile(out, true));
        }

        @Test
        @Disabled("Known bug: saveToTlkFile writes only the low byte of every int (OutputStream.write(int)) "
                + "and keeps entries in HashMaps, so the TLK it writes cannot be loaded back")
        void savedTlkLoadsBack() throws Exception {
            Path out = dir.resolve("saved.tlk");
            load(small()).saveToTlkFile(out.toString(), true);
            TlkFile t = new TlkFile();
            t.loadTlkData(out.toString());
            assertEquals(6, TlkFileTest.header(t).entry1Count);
            assertEquals(2, TlkFileTest.header(t).entry2Count);
            assertEquals(Arrays.asList("Hello", CYRILLIC, null, "Male", "", "Line one\nLine two\t(tab)",
                    "Hello, <Shepard> & co.", "Female"), texts(t));
        }

        @Test
        @Disabled("Known bug: saveToTlkFile keeps the string refs in HashMaps, so their order is lost "
                + "(and the low-byte int bug prevents reloading)")
        void savedTlkKeepsThePositionOrder() throws Exception {
            int[] ids = {1000, 3, 77, 500000, 42, 9, 65536, 12};
            List<String> strings = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                strings.add(entry(ids[i], i, "s" + i));
            }
            Path out = dir.resolve("ordered.tlk");
            load(xml("1.0.4", strings.toArray(new String[0]))).saveToTlkFile(out.toString(), true);
            TlkFile t = new TlkFile();
            t.loadTlkData(out.toString());
            List<TlkFile.TlkStringRef> refs = TlkFileTest.refs(t);
            for (int i = 0; i < ids.length; i++) {
                assertEquals(ids[i], refs.get(i).stringId, "position " + i);
                assertEquals("s" + i, refs.get(i).data);
            }
        }

        @Test
        @Disabled("Bug: a third entry with the same id silently replaces the second (female) one instead of "
                + "being rejected")
        void thirdOccurrenceOfAnIdIsRejected() throws Exception {
            Path in = xml("1.0.4", entry(5, 0, "one"), entry(5, 1, "two"), entry(5, 2, "three"));
            String out = dir.resolve("dup.tlk").toString();
            assertThrows(Exception.class, () -> load(in).saveToTlkFile(out, true));
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
