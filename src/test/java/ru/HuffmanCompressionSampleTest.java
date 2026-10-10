package ru;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link HuffmanCompression} on the XML exported from the real {@code files/BIOGame_RUS.tlk}
 * (TLK -> XML -> TLK). The XML is produced once per JVM by {@link SampleFixture}.
 */
class HuffmanCompressionSampleTest {

    /** Distinct characters in the sample's texts, plus the terminator. */
    private static final int ALPHABET_SIZE = SampleFixture.TREE_NODE_COUNT + 1;

    private static HuffmanCompression hc;

    @BeforeAll
    static void loadSampleXml() throws Exception {
        Path xml = SampleFixture.xml();
        HuffmanCompression h = new HuffmanCompression();
        h.loadInputData(xml.toString(), FileFormat.XML, false);
        hc = h;
    }

    @Test
    void loadsEveryEntryInPositionOrder() {
        List<HuffmanAccess.Entry> entries = HuffmanAccess.entries(hc);
        assertEquals(SampleFixture.REF_COUNT, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i, entries.get(i).position);
        }
        assertEquals("1.0.4", HuffmanAccess.version(hc));
    }

    @Test
    void entriesMatchTheOriginalTlk() throws Exception {
        List<HuffmanAccess.Entry> entries = HuffmanAccess.entries(hc);
        List<TlkFile.TlkStringRef> refs = SampleFixture.refs();
        for (int i = 0; i < entries.size(); i++) {
            HuffmanAccess.Entry e = entries.get(i);
            TlkFile.TlkStringRef r = refs.get(i);
            String expected = r.data == null ? "-1" : r.data + '\0';
            if (e.id != r.stringId || !expected.equals(e.data)) {
                fail("mismatch at position " + i + ": " + e.id + " / " + e.data);
            }
        }
    }

    @Test
    void buildsACodeForTheWholeAlphabet() throws Exception {
        Map<Character, BitArray> codes = HuffmanAccess.codes(hc);
        assertEquals(ALPHABET_SIZE, codes.size());
        assertEquals(HuffmanAccess.frequencies(hc).keySet(), codes.keySet());
        HuffmanAccess.assertPrefixFree(codes);
        assertEquals(1.0, HuffmanAccess.kraftSum(codes), 1e-12);
        Map<Character, Integer> freq = HuffmanAccess.frequencies(hc);
        assertEquals(HuffmanAccess.optimalBits(freq), HuffmanAccess.encodedBits(freq, codes));
        // one terminator per string with text
        assertEquals(Integer.valueOf(SampleFixture.REF_COUNT - SampleFixture.REFS_WITHOUT_TEXT), freq.get('\0'));
    }

    @Test
    void treeBufferHasOneNodePerInnerNode() throws Exception {
        List<Integer> tree = HuffmanAccess.treeBuffer(hc);
        assertEquals(2 * SampleFixture.TREE_NODE_COUNT, tree.size());
        HuffmanAccess.assertTreeDecodesCodes(tree, HuffmanAccess.codes(hc));
    }

    @Test
    void reEncodedSampleDecodesToTheOriginalStrings(@TempDir Path dir) throws Exception {
        Path tlk = HuffmanAccess.oracleTlk(hc).writeTo(dir.resolve("reencoded.tlk"));
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString(), true);
        assertEquals(SampleFixture.MALE_COUNT, TlkFileTest.header(t).entry1Count);
        assertEquals(SampleFixture.FEMALE_COUNT, TlkFileTest.header(t).entry2Count);
        assertEquals(SampleFixture.TREE_NODE_COUNT, TlkFileTest.header(t).treeNodeCount);
        assertSameStrings(SampleFixture.refs(), TlkFileTest.refs(t));
    }

    @Test
    void debugVersionPrefixesOnlyLocalizableStrings() throws Exception {
        HuffmanCompression debug = new HuffmanCompression();
        debug.loadInputData(SampleFixture.xml().toString(), FileFormat.XML, true);
        List<HuffmanAccess.Entry> entries = HuffmanAccess.entries(debug);
        assertEquals("(#218733) Капитан Бейли\0", entries.get(1000).data);
        assertEquals("Male\0", entries.get(SampleFixture.MALE_COUNT - 1).data);
        assertEquals("-1", entries.get(315).data);
        // the prefix characters are now part of the alphabet
        assertTrue(HuffmanAccess.codes(debug).containsKey('#'));
    }

    @Test
    void saveToTlkFileWritesTheEncodedData(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("saved.tlk");
        hc.saveToTlkFile(out.toString(), true);
        byte[] written = Files.readAllBytes(out);
        byte[] data = HuffmanAccess.expectedDataBlock(hc);
        assertArrayEquals(data, Arrays.copyOfRange(written, written.length - data.length, written.length));
    }

    @Test
    @Disabled("Known bug: saveToTlkFile writes only the low byte of every int and keeps entries in "
            + "HashMaps (order lost), so TLK -> XML -> TLK does not reproduce the strings")
    void tlkToXmlToTlkRoundTrip(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("saved.tlk");
        hc.saveToTlkFile(out.toString(), true);
        TlkFile t = new TlkFile();
        t.loadTlkData(out.toString(), true);
        TlkFile.TlkHeader h = TlkFileTest.header(t);
        assertEquals(SampleFixture.MAGIC, h.magic);
        assertEquals(SampleFixture.VERSION, h.ver);
        assertEquals(SampleFixture.MIN_VERSION, h.minVer);
        assertEquals(SampleFixture.MALE_COUNT, h.entry1Count);
        assertEquals(SampleFixture.FEMALE_COUNT, h.entry2Count);
        assertEquals(SampleFixture.TREE_NODE_COUNT, h.treeNodeCount);
        assertSameStrings(SampleFixture.refs(), TlkFileTest.refs(t));
    }

    private static void assertSameStrings(List<TlkFile.TlkStringRef> expected, List<TlkFile.TlkStringRef> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            TlkFile.TlkStringRef e = expected.get(i);
            TlkFile.TlkStringRef a = actual.get(i);
            boolean same = e.stringId == a.stringId
                    && (e.data == null ? a.data == null : e.data.equals(a.data))
                    && (e.bitOffset < 0) == (a.bitOffset < 0);
            if (!same) {
                fail("mismatch at position " + i + ": expected " + e.stringId + "=" + e.data
                        + " but was " + a.stringId + "=" + a.data);
            }
        }
    }
}
