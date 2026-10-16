package ru;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link TlkFile} against the real {@code files/BIOGame_RUS.tlk}. The file is loaded and
 * exported once per JVM by {@link SampleFixture}.
 */
class TlkFileSampleTest {

    @BeforeAll
    static void loadSample() throws IOException {
        SampleFixture.tlk();
    }

    private static TlkFile.TlkStringRef ref(int position) throws IOException {
        return SampleFixture.refs().get(position);
    }

    @Test
    void headerValues() throws IOException {
        TlkFile.TlkHeader h = TlkFileTest.header(SampleFixture.tlk());
        assertEquals(SampleFixture.MAGIC, h.magic);
        assertEquals(SampleFixture.VERSION, h.ver);
        assertEquals(SampleFixture.MIN_VERSION, h.minVer);
        assertEquals(SampleFixture.MALE_COUNT, h.entry1Count);
        assertEquals(SampleFixture.FEMALE_COUNT, h.entry2Count);
        assertEquals(SampleFixture.TREE_NODE_COUNT, h.treeNodeCount);
        assertEquals(SampleFixture.DATA_LEN, h.dataLen);
    }

    @Test
    void headerMatchesTheFileLayout() throws IOException {
        long expectedSize = TlkTestFile.HEADER_SIZE
                + 8L * SampleFixture.REF_COUNT
                + 8L * SampleFixture.TREE_NODE_COUNT
                + SampleFixture.DATA_LEN;
        assertEquals(expectedSize, Files.size(TestSupport.samplePath()));
    }

    @Test
    void everyRefIsLoadedInFileOrder() throws IOException {
        List<TlkFile.TlkStringRef> refs = SampleFixture.refs();
        assertEquals(SampleFixture.REF_COUNT, refs.size());
        int position = 0;
        for (TlkFile.TlkStringRef r : refs) {
            assertEquals(position++, r.position);
        }
    }

    @Test
    void refsWithoutTextAreExactlyThoseWithNegativeIdAndOffset() throws IOException {
        int withoutText = 0;
        for (TlkFile.TlkStringRef r : SampleFixture.refs()) {
            boolean noText = r.data == null;
            assertEquals(noText, r.bitOffset < 0, "position " + r.position);
            assertEquals(noText, r.stringId < 0, "position " + r.position);
            if (noText) {
                withoutText++;
                assertEquals(-1, r.bitOffset);
            }
        }
        assertEquals(SampleFixture.REFS_WITHOUT_TEXT, withoutText);
    }

    @Test
    void spotCheckDecodedStrings() throws IOException {
        TlkFile.TlkStringRef first = ref(0);
        assertEquals(34931, first.stringId);
        assertTrue(first.data.startsWith("Ваши родители служили в войсках Альянса."), first.data);
        assertTrue(first.data.endsWith("в возрасте 18 лет."), first.data);

        assertEquals(155051, ref(100).stringId);
        assertEquals("$703485", ref(100).data);

        assertEquals(218733, ref(1000).stringId);
        assertEquals("Капитан Бейли", ref(1000).data);

        TlkFile.TlkStringRef noText = ref(315);
        assertEquals(-2147319074, noText.stringId);
        assertEquals(-1, noText.bitOffset);
        assertNull(noText.data);

        // last male entry and its female counterpart
        assertEquals(134217731, ref(SampleFixture.MALE_COUNT - 1).stringId);
        assertEquals("Male", ref(SampleFixture.MALE_COUNT - 1).data);
        assertEquals(134217731, ref(SampleFixture.REF_COUNT - 1).stringId);
        assertEquals("Female", ref(SampleFixture.REF_COUNT - 1).data);

        TlkFile.TlkStringRef firstFemale = ref(SampleFixture.MALE_COUNT);
        assertEquals(263408, firstFemale.stringId);
        assertTrue(firstFemale.data.startsWith("После опустошительной атаки гетов на Цитадель прошел месяц."));
        assertTrue(firstFemale.data.contains("\n\nСовет, во главе которого"), firstFemale.data);
    }

    @Test
    void maleIdsAreUniqueAndFemaleIdsRepeatMaleOnes() throws IOException {
        List<TlkFile.TlkStringRef> refs = SampleFixture.refs();
        Set<Integer> male = new HashSet<>();
        for (int i = 0; i < SampleFixture.MALE_COUNT; i++) {
            assertTrue(male.add(refs.get(i).stringId), "duplicate male id at " + i);
        }
        for (int i = SampleFixture.MALE_COUNT; i < refs.size(); i++) {
            assertTrue(male.contains(refs.get(i).stringId), "female-only id at " + i);
        }
    }

    @Test
    void huffmanTreeIsAProperBinaryTree() throws IOException {
        List<TlkFile.HuffmanNode> tree = TlkFileTest.tree(SampleFixture.tlk());
        assertEquals(SampleFixture.TREE_NODE_COUNT, tree.size());
        int[] referenced = new int[tree.size()];
        Set<Integer> leaves = new HashSet<>();
        for (TlkFile.HuffmanNode node : tree) {
            for (int child : new int[]{node.leftNodeId, node.rightNodeId}) {
                if (child >= 0) {
                    assertTrue(child > 0 && child < tree.size(), "bad child " + child);
                    referenced[child]++;
                } else {
                    assertTrue(child >= -1 - 0xffff, "bad leaf " + child);
                    assertTrue(leaves.add(child), "duplicate leaf " + child);
                }
            }
        }
        assertEquals(0, referenced[0], "the root must not be a child");
        for (int i = 1; i < referenced.length; i++) {
            assertEquals(1, referenced[i], "node " + i);
        }
        // a full binary tree with n inner nodes has n + 1 leaves, one per character incl. '\0'
        assertEquals(SampleFixture.TREE_NODE_COUNT + 1, leaves.size());
        assertTrue(leaves.contains(-1), "no terminator leaf");
    }

    @Test
    void dataBlockIsKeptAsBits() throws IOException {
        assertEquals(8L * SampleFixture.DATA_LEN, TlkFileTest.bits(SampleFixture.tlk()).length());
    }

    @Test
    void xmlExportContainsEveryRef() throws Exception {
        ExportedXml xml = SampleFixture.parsedXml();
        assertEquals("tlkFile", xml.rootName);
        assertEquals("1.0.4", xml.toolVersion);
        assertEquals(SampleFixture.REF_COUNT, xml.strings.size());

        List<TlkFile.TlkStringRef> refs = SampleFixture.refs();
        int i = 0;
        for (ExportedXml.Entry e : xml.strings) {
            TlkFile.TlkStringRef r = refs.get(i);
            if (!e.children.equals(Arrays.asList("id", "position", "data"))
                    || !String.valueOf(r.stringId).equals(e.id)
                    || !String.valueOf(i).equals(e.position)
                    || !(r.data == null ? "-1" : r.data).equals(e.data)) {
                fail("mismatch at position " + i + ": id=" + e.id + " position=" + e.position + " data=" + e.data);
            }
            i++;
        }
    }

    @Test
    void xmlExportMarksTheSections() throws Exception {
        assertEquals(Arrays.asList(
                "Male entries section begin (ends at position 103969)",
                "Male entries section end",
                "Female entries section begin (ends at position 105123)",
                "Female entries section end"), SampleFixture.parsedXml().comments);
    }

    @Test
    void xmlExportSpotChecks() throws Exception {
        List<ExportedXml.Entry> strings = SampleFixture.parsedXml().strings;
        assertEquals("218733", strings.get(1000).id);
        assertEquals("Капитан Бейли", strings.get(1000).data);
        assertEquals("-2147319074", strings.get(315).id);
        assertEquals("-1", strings.get(315).data);
        assertEquals("Female", strings.get(SampleFixture.REF_COUNT - 1).data);
    }

    @Test
    void textExportOfTheFirstEntries(@TempDir Path dir) throws Exception {
        // exporting all 105,124 entries as text takes ~20 s (the file is reopened per line), so
        // export a copy that holds only the first 400 refs
        List<TlkFile.TlkStringRef> firstRefs = SampleFixture.refs().subList(0, 400);
        TlkFile part = withRefs(TlkFileTest.header(SampleFixture.tlk()), firstRefs);

        Path out = dir.resolve("part.txt");
        part.storeToFile(out.toString(), FileFormat.TXT, null);

        StringBuilder expected = new StringBuilder();
        for (TlkFile.TlkStringRef r : firstRefs) {
            expected.append(r.stringId).append(": ").append(r.data).append("\r\n");
        }
        assertTrue(expected.indexOf("-2147319074: null\r\n") >= 0);
        assertArrayEquals(expected.toString().getBytes(Charset.defaultCharset()), Files.readAllBytes(out));
    }

    @Test
    void xmlExportTimeGrowsLinearly(@TempDir Path dir) throws Exception {
        List<TlkFile.TlkStringRef> all = SampleFixture.refs();
        TlkFile.TlkHeader header = TlkFileTest.header(SampleFixture.tlk());
        TlkFile eighth = withRefs(header, all.subList(0, all.size() / 8));
        TlkFile full = withRefs(header, all);
        String out = dir.resolve("timed.xml").toString();

        full.storeToFile(out, FileFormat.XML, null);   // warm-up
        long eighthTime = fastestExport(eighth, out);
        long fullTime = fastestExport(full, out);

        // linear: ~8x; quadratic: ~64x
        assertTrue(fullTime < 24 * eighthTime,
                "8x the entries took " + (double) fullTime / eighthTime + "x as long");
    }

    /** Shortest of three XML exports, in nanoseconds. */
    private static long fastestExport(TlkFile t, String out) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            t.storeToFile(out, FileFormat.XML, null);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    /**
     * A TlkFile holding the given refs, in a LinkedList so that an export that indexes the list
     * instead of iterating it shows up as quadratic.
     */
    private static TlkFile withRefs(TlkFile.TlkHeader header, List<TlkFile.TlkStringRef> refs) {
        TlkFile t = new TlkFile();
        TestSupport.setField(t, "header", header);
        TestSupport.setField(t, "stringRefs", new LinkedList<>(refs));
        return t;
    }

    @Test
    void sampleFixtureExportIsShared() throws Exception {
        assertNotNull(SampleFixture.xml());
        assertTrue(Files.size(SampleFixture.xml()) > 10_000_000L);
    }
}
