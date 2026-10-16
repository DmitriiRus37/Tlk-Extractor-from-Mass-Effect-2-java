package ru;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TlkFileTest {

    private static final String CYRILLIC = "Привет, мир!";
    private static final String EMOJI = "\uD83D\uDE00 smile";

    @TempDir
    Path dir;

    private int fileCounter;

    // ---------------------------------------------------------------- helpers

    static TlkFile.TlkHeader header(TlkFile t) {
        return TestSupport.field(t, "header");
    }

    /** A copy of the loaded string refs, or null. */
    static List<TlkFile.TlkStringRef> refs(TlkFile t) {
        List<TlkFile.TlkStringRef> refs = TestSupport.field(t, "stringRefs");
        return refs == null ? null : new ArrayList<>(refs);
    }

    static List<TlkFile.HuffmanNode> tree(TlkFile t) {
        return TestSupport.field(t, "characterTree");
    }

    static BitArray bits(TlkFile t) {
        return TestSupport.field(t, "bits");
    }

    static List<String> texts(TlkFile t) {
        List<String> result = new ArrayList<>();
        for (TlkFile.TlkStringRef r : refs(t)) {
            result.add(r.data);
        }
        return result;
    }

    private Path write(byte[] content) throws IOException {
        return Files.write(dir.resolve("file" + (fileCounter++) + ".tlk"), content);
    }

    private Path write(TlkTestFile file) throws IOException {
        return write(file.build());
    }

    private TlkFile load(Path file) throws IOException {
        TlkFile t = new TlkFile();
        t.loadTlkData(file.toString());
        return t;
    }

    /** Asserts that loading fails with an exception of the given type whose message contains {@code text}. */
    private <T extends IOException> T assertLoadFails(Class<T> type, Path file, String text) {
        T e = assertThrows(type, () -> load(file));
        assertTrue(e.getMessage().contains(text), e.getMessage());
        return e;
    }

    private static BitArray code(String zeroesAndOnes) {
        boolean[] b = new boolean[zeroesAndOnes.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = zeroesAndOnes.charAt(i) == '1';
        }
        return new BitArray(b);
    }

    private TlkFile load(TlkTestFile file) throws IOException {
        return load(write(file));
    }

    /** Three male refs (one without text) and two female refs. */
    private static TlkTestFile typicalFile() {
        return new TlkTestFile()
                .male(100, "Hello")
                .male(101, CYRILLIC)
                .maleRaw(-7, -1)
                .female(100, "Hello, ma'am")
                .female(102, "Tab\tand\nnewline");
    }

    private static InputStream in(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    // ---------------------------------------------------------------- binary records

    @Nested
    class ReadInt32 {

        @Test
        void readsLittleEndian() throws IOException {
            assertEquals(0x01020304, TlkFile.readInt32(in(new byte[]{4, 3, 2, 1})));
            assertEquals(SampleFixture.MAGIC, TlkFile.readInt32(in("Tlk\0".getBytes(StandardCharsets.US_ASCII))));
        }

        @Test
        void boundaryValues() throws IOException {
            assertEquals(0, TlkFile.readInt32(in(TlkTestFile.intsLE(0))));
            assertEquals(-1, TlkFile.readInt32(in(TlkTestFile.intsLE(-1))));
            assertEquals(Integer.MIN_VALUE, TlkFile.readInt32(in(TlkTestFile.intsLE(Integer.MIN_VALUE))));
            assertEquals(Integer.MAX_VALUE, TlkFile.readInt32(in(TlkTestFile.intsLE(Integer.MAX_VALUE))));
            assertEquals(0xff, TlkFile.readInt32(in(TlkTestFile.intsLE(0xff))));
            assertEquals(0x80000000, TlkFile.readInt32(in(new byte[]{0, 0, 0, (byte) 0x80})));
        }

        @Test
        void consecutiveReadsAdvanceByFourBytes() throws IOException {
            InputStream s = in(TlkTestFile.intsLE(1, -2, 3));
            assertEquals(1, TlkFile.readInt32(s));
            assertEquals(-2, TlkFile.readInt32(s));
            assertEquals(3, TlkFile.readInt32(s));
            assertEquals(-1, s.read());
        }

        @Test
        void incompleteIntIsEof() {
            assertThrows(EOFException.class, () -> TlkFile.readInt32(in(new byte[0])));
            assertThrows(EOFException.class, () -> TlkFile.readInt32(in(new byte[3])));
        }
    }

    @Nested
    class Records {

        @Test
        void headerReadsSevenIntsInOrder() throws IOException {
            TlkFile.TlkHeader h = new TlkFile.TlkHeader(in(TlkTestFile.intsLE(1, 2, 3, 4, 5, 6, 7, 99)));
            assertEquals(1, h.magic);
            assertEquals(2, h.ver);
            assertEquals(3, h.minVer);
            assertEquals(4, h.entry1Count);
            assertEquals(5, h.entry2Count);
            assertEquals(6, h.treeNodeCount);
            assertEquals(7, h.dataLen);
        }

        @Test
        void truncatedHeaderIsEof() {
            assertThrows(EOFException.class, () -> new TlkFile.TlkHeader(in(TlkTestFile.intsLE(1, 2, 3, 4, 5, 6))));
        }

        @Test
        void stringRefReadsIdThenOffset() throws IOException {
            TlkFile.TlkStringRef r = new TlkFile.TlkStringRef(in(TlkTestFile.intsLE(-2147319074, -1)));
            assertEquals(-2147319074, r.stringId);
            assertEquals(-1, r.bitOffset);
            assertNull(r.data);
            assertEquals(0, r.position);
            assertThrows(EOFException.class, () -> new TlkFile.TlkStringRef(in(TlkTestFile.intsLE(5))));
        }

        @Test
        void huffmanNodeReadsLeftThenRight() throws IOException {
            TlkFile.HuffmanNode n = new TlkFile.HuffmanNode(in(TlkTestFile.intsLE(3, -33)));
            assertEquals(3, n.leftNodeId);
            assertEquals(-33, n.rightNodeId);
            assertThrows(EOFException.class, () -> new TlkFile.HuffmanNode(in(new byte[7])));
        }

        @Test
        void leafIdsMapToCharacters() {
            assertEquals('\0', TlkFile.leafCharacter(-1));
            assertEquals(' ', TlkFile.leafCharacter(-33));
            assertEquals('\uffff', TlkFile.leafCharacter(-65536));
            for (char c : new char[]{'\0', ' ', 'Ж', '\uffff'}) {
                assertEquals(c, TlkFile.leafCharacter(TlkFile.leafId(c)));
            }
        }
    }

    // ---------------------------------------------------------------- loading

    @Nested
    class Loading {

        @Test
        void readsTheHeader() throws IOException {
            TlkTestFile f = typicalFile();
            byte[] content = f.build();
            TlkFile t = load(write(content));
            TlkFile.TlkHeader h = header(t);
            assertEquals(TlkTestFile.MAGIC, h.magic);
            assertEquals(3, h.ver);
            assertEquals(2, h.minVer);
            assertEquals(3, h.entry1Count);
            assertEquals(2, h.entry2Count);
            assertEquals((f.dataOffset() - f.treeOffset()) / 8, h.treeNodeCount);
            assertEquals(content.length - f.dataOffset(), h.dataLen);
            assertEquals(h.treeNodeCount, tree(t).size());
            assertEquals(h.dataLen * 8, bits(t).length());
        }

        @Test
        void versionFieldsAreNotValidated() throws IOException {
            TlkFile t = load(typicalFile().versions(7, 0));
            assertEquals(7, header(t).ver);
            assertEquals(0, header(t).minVer);
            assertEquals("Hello", refs(t).get(0).data);
        }

        @Test
        void decodesAllRefsInFileOrder() throws IOException {
            TlkFile t = load(typicalFile());
            List<TlkFile.TlkStringRef> refs = refs(t);
            assertEquals(5, refs.size());
            int[] ids = {100, 101, -7, 100, 102};
            for (int i = 0; i < refs.size(); i++) {
                assertEquals(i, refs.get(i).position);
                assertEquals(ids[i], refs.get(i).stringId);
            }
            assertEquals(Arrays.asList("Hello", CYRILLIC, null, "Hello, ma'am", "Tab\tand\nnewline"), texts(t));
        }

        @Test
        void refWithNegativeOffsetHasNoText() throws IOException {
            TlkFile t = load(new TlkTestFile().male(1, "a").maleRaw(-2, -1).maleRaw(3, Integer.MIN_VALUE));
            assertEquals(-1, refs(t).get(1).bitOffset);
            assertNull(refs(t).get(1).data);
            assertNull(refs(t).get(2).data);
        }

        @Test
        void emptyStringDecodesToEmptyText() throws IOException {
            TlkFile t = load(new TlkTestFile().male(1, "").male(2, "x").male(3, ""));
            assertEquals(Arrays.asList("", "x", ""), texts(t));
        }

        @Test
        void unicodeIncludingSurrogatesAndBoundaryChars() throws IOException {
            String boundary = "\u0001\u007f\u0080ÿĀЖ№\ud7ff\ue000\ufffe\uffff";
            TlkFile t = load(new TlkTestFile().male(1, EMOJI).male(2, boundary).male(3, CYRILLIC + " 中文"));
            assertEquals(Arrays.asList(EMOJI, boundary, CYRILLIC + " 中文"), texts(t));
        }

        @Test
        void singleCharacterAlphabet() throws IOException {
            // only '\0' is used: the tree is a single node with the terminator on both sides
            TlkFile t = load(new TlkTestFile().male(1, "").female(1, ""));
            assertEquals(1, tree(t).size());
            assertEquals(Arrays.asList("", ""), texts(t));
        }

        @Test
        void refsCanShareAnOffset() throws IOException {
            TlkFile t = load(new TlkTestFile().male(1, "shared").maleSharing(2, 0, 0).femaleSharing(1, 0, 0));
            assertEquals(refs(t).get(0).bitOffset, refs(t).get(1).bitOffset);
            assertEquals(Arrays.asList("shared", "shared", "shared"), texts(t));
        }

        @Test
        void refIntoTheMiddleOfAStringYieldsItsTail() throws IOException {
            TlkFile t = load(new TlkTestFile().male(1, "Hello").male(2, "World").maleSharing(3, 0, 3).maleSharing(4, 1, 5));
            assertEquals(Arrays.asList("Hello", "World", "lo", ""), texts(t));
        }

        @Test
        void longStringAcrossManyBytes() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                sb.append((char) ('a' + i % 26)).append(i % 7 == 0 ? "Ж" : "");
            }
            TlkFile t = load(new TlkTestFile().male(1, sb.toString()).male(2, "end"));
            assertEquals(Arrays.asList(sb.toString(), "end"), texts(t));
        }

        @Test
        void loadingAgainReplacesPreviousContent() throws IOException {
            TlkFile t = load(typicalFile());
            t.loadTlkData(write(new TlkTestFile().male(9, "other")).toString());
            assertEquals(1, refs(t).size());
            assertEquals("other", refs(t).get(0).data);
            assertEquals(1, header(t).entry1Count);
        }

        @Test
        void zeroEntryFile() throws IOException {
            TlkFile t = load(new TlkTestFile().treeNodeCount(0).dataLen(0));
            assertEquals(0, header(t).entry1Count);
            assertEquals(0, header(t).entry2Count);
            assertTrue(refs(t).isEmpty());
            assertEquals(0, bits(t).length());
        }

        @Test
        void headerOnlyFileWithZeroCounts() throws IOException {
            TlkFile t = load(write(TlkTestFile.intsLE(TlkTestFile.MAGIC, 3, 2, 0, 0, 0, 0)));
            assertTrue(refs(t).isEmpty());
            assertTrue(tree(t).isEmpty());
        }

        @Test
        void missingFile() {
            assertThrows(NoSuchFileException.class, () -> new TlkFile().loadTlkData(dir.resolve("nope.tlk").toString()));
        }

        @Test
        void directoryInsteadOfFile() {
            assertThrows(IOException.class, () -> new TlkFile().loadTlkData(dir.toString()));
        }

        @Test
        void badMagicIsRejected() throws IOException {
            Path f = write(typicalFile().magic(0x12345678));
            assertLoadFails(IOException.class, f, "Not a Mass Effect 2 TLK file (magic number 0x12345678");
        }

        @Test
        void xboxMagicIsRejected() throws IOException {
            Path f = write(typicalFile().magic(TlkTestFile.XBOX_MAGIC));
            assertLoadFails(IOException.class, f, "Xbox 360");
        }

        @Test
        void allZeroFileIsRejected() throws IOException {
            assertLoadFails(IOException.class, write(new byte[64]), "magic number 0x0 ");
        }

        @Test
        void emptyFileIsEof() throws IOException {
            assertLoadFails(EOFException.class, write(new byte[0]), "too short for a TLK header");
        }

        @Test
        void fileTruncatedInHeaderIsEof() throws IOException {
            assertLoadFails(EOFException.class, write(Arrays.copyOf(typicalFile().build(), 27)), "27 bytes");
        }

        @Test
        void fileTruncatedInStringRefsIsEof() throws IOException {
            Path f = write(Arrays.copyOf(typicalFile().build(), TlkTestFile.HEADER_SIZE + 12));
            assertLoadFails(EOFException.class, f, "Truncated TLK file");
        }

        @Test
        void fileTruncatedInTreeIsEof() throws IOException {
            TlkTestFile file = typicalFile();
            byte[] full = file.build();
            Path f = write(Arrays.copyOf(full, file.treeOffset() + 12));
            assertLoadFails(EOFException.class, f, "the file has " + (file.treeOffset() + 12));
        }

        @Test
        void dataWithoutTreeIsRejected() throws IOException {
            Path f = write(typicalFile().treeNodeCount(0));
            assertLoadFails(IOException.class, f, "no Huffman tree");
        }

        @Test
        void negativeCountsAreRejected() throws IOException {
            assertLoadFails(IOException.class, write(typicalFile().entry2Count(-1)), "negative count");
            assertLoadFails(IOException.class, write(typicalFile().dataLen(-5)), "negative count");
        }

        @Test
        void hugeCountsAreRejectedWithoutAllocating() throws IOException {
            assertLoadFails(EOFException.class, write(typicalFile().entry1Count(Integer.MAX_VALUE)), "Truncated");
            assertLoadFails(EOFException.class, write(typicalFile().dataLen(Integer.MAX_VALUE)), "Truncated");
        }

        @Test
        void treeNodePointingToAMissingNodeIsRejected() throws IOException {
            Path f = write(new TlkTestFile().tree(Arrays.asList(5, -1), Collections.singletonMap('\0', code("1"))).male(1, ""));
            assertLoadFails(IOException.class, f, "node 0 points to node 5");
        }

        @Test
        void stringWithoutTerminatorIsRejected() throws IOException {
            Path f = write(new TlkTestFile().male(1, "abc").maleRaw(2, 100_000));
            assertLoadFails(IOException.class, f, "bit offset 100000 has no terminator");
        }

        @Test
        void trailingBytesAreIgnored() throws IOException {
            byte[] full = typicalFile().build();
            TlkFile t = load(write(Arrays.copyOf(full, full.length + 10)));
            assertEquals("Hello", refs(t).get(0).data);
        }

        @Test
        void failedLoadKeepsThePreviousContent() throws IOException {
            TlkFile t = load(typicalFile());
            Path bad = write(typicalFile().magic(1));
            assertThrows(IOException.class, () -> t.loadTlkData(bad.toString()));
            assertEquals(5, refs(t).size());
            assertEquals("Hello", refs(t).get(0).data);
        }

        @Test
        void fileTruncatedInDataBlockIsEof() throws IOException {
            TlkTestFile file = typicalFile();
            byte[] full = file.build();
            assertTrue(full.length - file.dataOffset() > 2);
            Path f = write(Arrays.copyOf(full, file.dataOffset() + 1));
            assertLoadFails(EOFException.class, f, "Truncated TLK file");
        }

        @Test
        void loadingDoesNotLeakFileHandles() throws IOException {
            Path f = write(typicalFile());
            load(f);
            long before = TestSupport.openFileDescriptorCount();
            assumeTrue(before >= 0, "open file descriptor count not available");
            for (int i = 0; i < 25; i++) {
                load(f);
            }
            long leaked = TestSupport.openFileDescriptorCount() - before;
            assertTrue(leaked < 5, "file descriptors leaked: " + leaked);
        }
    }

    // ---------------------------------------------------------------- XML export

    @Nested
    class XmlExport {

        private ExportedXml export(TlkFile t) throws Exception {
            Path out = dir.resolve("out" + (fileCounter++) + ".xml");
            t.storeToFile(out.toString(), FileFormat.XML, null);
            return ExportedXml.parse(out);
        }

        @Test
        void writesOneStringElementPerRef() throws Exception {
            ExportedXml xml = export(load(typicalFile()));
            assertEquals("tlkFile", xml.rootName);
            assertEquals("1.0.4", xml.toolVersion);
            assertEquals(5, xml.strings.size());
            String[] ids = {"100", "101", "-7", "100", "102"};
            String[] data = {"Hello", CYRILLIC, "-1", "Hello, ma'am", "Tab\tand\nnewline"};
            for (int i = 0; i < 5; i++) {
                ExportedXml.Entry e = xml.strings.get(i);
                assertEquals(Arrays.asList("id", "position", "data"), e.children);
                assertEquals(ids[i], e.id);
                assertEquals(String.valueOf(i), e.position);
                assertEquals(data[i], e.data);
            }
        }

        @Test
        void marksTheSectionsWithComments() throws Exception {
            ExportedXml xml = export(load(typicalFile()));
            assertEquals(Arrays.asList(
                    "Male entries section begin (ends at position 2)",
                    "Male entries section end",
                    "Female entries section begin (ends at position 4)",
                    "Female entries section end"), xml.comments);
        }

        @Test
        void isIndentedWithFourSpaces() throws Exception {
            Path out = dir.resolve("indent.xml");
            load(typicalFile()).storeToFile(out.toString(), FileFormat.XML, null);
            String content = TestSupport.readUtf8(out);
            assertTrue(content.contains("\n    <string>"), content);
            assertTrue(content.contains("\n        <id>100</id>"), content);
        }

        @Test
        void escapesMarkupCharacters() throws Exception {
            String markup = "<b>Tom & \"Jerry\"</b> 'quoted' a > b ]]>";
            ExportedXml xml = export(load(new TlkTestFile().male(1, markup)));
            assertEquals(markup, xml.strings.get(0).data);
        }

        @Test
        void keepsUnicodeTabsAndNewlines() throws Exception {
            String text = CYRILLIC + "\n\t" + EMOJI + " №5 ©";
            ExportedXml xml = export(load(new TlkTestFile().male(1, text)));
            assertEquals(text, xml.strings.get(0).data);
        }

        @Test
        void emptyTextIsAnEmptyDataElement() throws Exception {
            ExportedXml xml = export(load(new TlkTestFile().male(1, "").male(2, "x")));
            assertEquals("", xml.strings.get(0).data);
            assertEquals("x", xml.strings.get(1).data);
        }

        @Test
        void keepsLeadingAndTrailingWhitespace() throws Exception {
            ExportedXml xml = export(load(new TlkTestFile().male(1, "  padded\t").male(2, "\nx\n")));
            assertEquals("  padded\t", xml.strings.get(0).data);
            assertEquals("\nx\n", xml.strings.get(1).data);
        }

        @Test
        void textThatXmlCannotRepresentFailsTheExport() throws IOException {
            // U+0001 is not allowed in XML 1.0, not even as a character reference
            TlkFile t = load(new TlkTestFile().male(1, "bell\u0001"));
            String target = dir.resolve("control.xml").toString();
            assertThrows(RuntimeException.class, () -> t.storeToFile(target, FileFormat.XML, null));
        }

        @Test
        void replacesAnExistingFile() throws Exception {
            Path out = dir.resolve("existing.xml");
            TestSupport.writeUtf8(out, "<garbage>this is not a tlk export");
            load(typicalFile()).storeToFile(out.toString(), FileFormat.XML, null);
            ExportedXml xml = ExportedXml.parse(out);
            assertEquals(5, xml.strings.size());
            assertFalse(TestSupport.readUtf8(out).contains("garbage"));
        }

        @Test
        void zeroEntryFileGivesEmptyRoot() throws Exception {
            ExportedXml xml = export(load(new TlkTestFile().treeNodeCount(0).dataLen(0)));
            assertEquals("tlkFile", xml.rootName);
            assertEquals("1.0.4", xml.toolVersion);
            assertTrue(xml.strings.isEmpty());
        }

        @Test
        void unwritableTargetFails() throws IOException {
            TlkFile t = load(typicalFile());
            String target = dir.resolve("no-such-dir").resolve("out.xml").toString();
            assertThrows(IOException.class, () -> t.storeToFile(target, FileFormat.XML, null));
        }

        @Test
        @Disabled("Bug: the male-end/female-begin comments are only written if the female section is non-empty")
        void sectionCommentsAreBalancedWithoutFemaleEntries() throws Exception {
            ExportedXml xml = export(load(new TlkTestFile().male(1, "a").male(2, "b")));
            assertEquals(Arrays.asList(
                    "Male entries section begin (ends at position 1)",
                    "Male entries section end",
                    "Female entries section begin (ends at position 1)",
                    "Female entries section end"), xml.comments);
        }

        @Test
        @Disabled("Bug: storeToFile never closes the FileWriter (saveToXmlFile) and PrintWriter (prettyXmlFile)")
        void exportDoesNotLeakFileHandles() throws Exception {
            TlkFile t = load(typicalFile());
            Path out = dir.resolve("leak.xml");
            t.storeToFile(out.toString(), FileFormat.XML, null);
            long before = TestSupport.openFileDescriptorCount();
            assumeTrue(before >= 0, "open file descriptor count not available");
            for (int i = 0; i < 25; i++) {
                t.storeToFile(out.toString(), FileFormat.XML, null);
            }
            long leaked = TestSupport.openFileDescriptorCount() - before;
            assertTrue(leaked < 5, "file descriptors leaked: " + leaked);
        }

        @Test
        @Disabled("Bug: the XML is written with the platform default charset but declared UTF-8, so with a "
                + "non-UTF-8 default (JDK < 18 on Windows, or LANG=C) the export throws XMLStreamException")
        void exportIsUtf8WhateverTheDefaultCharset() throws Exception {
            Path tlk = write(new TlkTestFile().male(1, CYRILLIC));
            Path out = dir.resolve("latin1.xml");
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process p = new ProcessBuilder(javaBin, "-Dfile.encoding=ISO-8859-1", "-cp", classPath,
                    ExportMain.class.getName(), tlk.toString(), out.toString(), "XML")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(TestSupport.readFully(p.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "child JVM timed out");
            assertEquals(0, p.exitValue(), output);
            assertEquals(CYRILLIC, ExportedXml.parse(out).strings.get(0).data);
        }
    }

    // ---------------------------------------------------------------- text export

    @Nested
    class TextExport {

        private String export(TlkFile t, FileFormat format, String name) throws Exception {
            Path out = dir.resolve(name);
            t.storeToFile(out.toString(), format, null);
            return new String(Files.readAllBytes(out), Charset.defaultCharset());
        }

        private TlkTestFile asciiFile() {
            return new TlkTestFile()
                    .male(100, "Hello")
                    .maleRaw(-7, -1)
                    .male(101, "")
                    .female(100, "two\nlines");
        }

        @Test
        void writesIdColonTextLinesWithCrLf() throws Exception {
            assertEquals("100: Hello\r\n-7: null\r\n101: \r\n100: two\nlines\r\n",
                    export(load(asciiFile()), FileFormat.TXT, "out.txt"));
        }

        @Test
        void csvIsTheSamePlainListing() throws Exception {
            TlkFile t = load(asciiFile());
            assertEquals(export(t, FileFormat.TXT, "a.txt"), export(t, FileFormat.CSV, "a.csv"));
        }

        @Test
        void unicodeTextWithUtf8DefaultCharset() throws Exception {
            assumeTrue(StandardCharsets.UTF_8.equals(Charset.defaultCharset()), "default charset is not UTF-8");
            assertEquals("1: " + CYRILLIC + "\r\n", export(load(new TlkTestFile().male(1, CYRILLIC)), FileFormat.TXT, "u.txt"));
        }

        @Test
        void exportingAgainReplacesInsteadOfAppending() throws Exception {
            TlkFile t = load(asciiFile());
            String first = export(t, FileFormat.TXT, "twice.txt");
            assertEquals(first, export(t, FileFormat.TXT, "twice.txt"));
        }

        @Test
        void xmlCanBeReplacedByText() throws Exception {
            TlkFile t = load(asciiFile());
            Path out = dir.resolve("switch.out");
            t.storeToFile(out.toString(), FileFormat.XML, null);
            t.storeToFile(out.toString(), FileFormat.TXT, null);
            assertTrue(new String(Files.readAllBytes(out), Charset.defaultCharset()).startsWith("100: Hello\r\n"));
        }

        @Test
        @Disabled("Bug: exporting a TLK without entries as TXT creates no file at all")
        void zeroEntryFileGivesEmptyTextFile() throws Exception {
            TlkFile t = load(new TlkTestFile().treeNodeCount(0).dataLen(0));
            Path out = dir.resolve("empty.txt");
            t.storeToFile(out.toString(), FileFormat.TXT, null);
            assertTrue(Files.isRegularFile(out));
            assertEquals(0, Files.size(out));
        }

        @Test
        @Disabled("Bug: TXT export prints and swallows IOExceptions instead of reporting them")
        void unwritableTargetFails() throws IOException {
            TlkFile t = load(asciiFile());
            String target = dir.resolve("no-such-dir").resolve("out.txt").toString();
            assertThrows(IOException.class, () -> t.storeToFile(target, FileFormat.TXT, null));
        }
    }

    @Test
    void storeWithoutLoadingFails() {
        assertThrows(RuntimeException.class,
                () -> new TlkFile().storeToFile(dir.resolve("x.xml").toString(), FileFormat.XML, null));
    }

    @Test
    void freshInstanceHasNoData() {
        TlkFile t = new TlkFile();
        assertNull(header(t));
        assertNull(refs(t));
        assertNull(tree(t));
        assertNull(bits(t));
    }
}
