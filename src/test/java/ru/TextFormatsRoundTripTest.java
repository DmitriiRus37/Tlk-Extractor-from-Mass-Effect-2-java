package ru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLK -> TXT/CSV/XML -> TLK with {@link TlkFile} and {@link HuffmanCompression} on synthetic files
 * with awkward texts, and the validation that all formats share.
 */
class TextFormatsRoundTripTest {

    @TempDir
    Path dir;

    private int fileCounter;

    /** Texts that need escaping or quoting in at least one format; all valid XML. */
    private static TlkTestFile trickyFile() {
        return new TlkTestFile()
                .male(1, "Hello")
                .male(2, "Привет, мир! \"Шепард\"")
                .male(3, "")
                .maleRaw(-4, -1)
                .male(5, "line one\nline two\n")
                .male(6, "lone\rcarriage return")
                .male(7, "back\\slash \\N \\n \\u0041 \\")
                .male(8, "\\N")
                .male(9, "null")
                .male(10, "-1")
                .male(11, "tab\there, comma, \"quote\"")
                .male(12, "  leading and trailing  ")
                .male(13, "# not a comment")
                .male(14, "id: 5")
                .male(15, "😀 emoji")
                .male(16, " separators \u0085")
                .maleRaw(17, -1)
                .male(-18, "negative id with text")
                .male(0x8000001, "Male")
                .female(1, "Hello, ma'am")
                .femaleRaw(17, -1)
                .female(0x8000001, "Female");
    }

    private Path tlk(TlkTestFile file) throws IOException {
        return file.writeTo(dir.resolve("in" + (fileCounter++) + ".tlk"));
    }

    private static TlkFile load(Path tlk) throws IOException {
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        return t;
    }

    private Path export(TlkFile t, FileFormat format) throws IOException {
        Path out = dir.resolve("out" + (fileCounter++) + "." + format.getExtension());
        t.storeToFile(out.toString(), format, null);
        return out;
    }

    /** Imports a file with {@link HuffmanCompression} and loads the TLK file it writes. */
    private TlkFile reencode(Path text, FileFormat format) throws IOException {
        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(text.toString(), format, false);
        Path tlk = dir.resolve("re" + (fileCounter++) + ".tlk");
        hc.saveToTlkFile(tlk.toString(), null);
        return load(tlk);
    }

    private static List<String> describe(TlkFile t) {
        List<String> result = new ArrayList<>();
        TlkFile.TlkHeader h = TlkFileTest.header(t);
        result.add("sections " + h.entry1Count + "/" + h.entry2Count);
        for (TlkFile.TlkStringRef r : TlkFileTest.refs(t)) {
            result.add(r.position + ": " + r.stringId + " " + (r.bitOffset < 0) + " " + r.data);
        }
        return result;
    }

    @ParameterizedTest
    @EnumSource(FileFormat.class)
    void everyFormatRoundTripsEveryEntry(FileFormat format) throws IOException {
        TlkFile original = load(tlk(trickyFile()));
        TlkFile again = reencode(export(original, format), format);

        assertEquals(describe(original), describe(again));
        assertArrayEquals(Files.readAllBytes(export(original, FileFormat.XML)), Files.readAllBytes(export(again, FileFormat.XML)));
        assertArrayEquals(Files.readAllBytes(export(original, format)), Files.readAllBytes(export(again, format)));
    }

    @Test
    void txtAndCsvAlsoCarryCharactersXmlCannotRepresent() throws IOException {
        TlkFile original = load(tlk(new TlkTestFile().male(1, "bell\u0007 nul-free \u0001\u001F￾")));
        assertThrows(IOException.class, () -> export(original, FileFormat.XML));
        for (FileFormat format : Arrays.asList(FileFormat.TXT, FileFormat.CSV)) {
            assertEquals(describe(original), describe(reencode(export(original, format), format)));
        }
        TlkFile surrogate = load(tlk(new TlkTestFile().male(1, "half \uD800 pair")));
        assertEquals(describe(surrogate), describe(reencode(export(surrogate, FileFormat.TXT), FileFormat.TXT)));
    }

    @ParameterizedTest
    @EnumSource(FileFormat.class)
    void crLfBecomesLfInEveryFormat(FileFormat format) throws IOException {
        TlkFile original = load(tlk(new TlkTestFile().male(1, "a\r\nb\r\rc")));
        assertEquals("a\nb\r\rc", TlkFileTest.refs(reencode(export(original, format), format)).get(0).data);
    }

    @ParameterizedTest
    @EnumSource(FileFormat.class)
    void debugPrefixIsAddedInEveryFormat(FileFormat format) throws Exception {
        Path text = export(load(tlk(new TlkTestFile().male(100, "Hello").maleRaw(-7, -1).male(0x8000001, "Male"))), format);
        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(text.toString(), format, true);
        List<String> data = new ArrayList<>();
        for (HuffmanAccess.Entry e : HuffmanAccess.entries(hc)) {
            data.add(e.data);
        }
        assertEquals(Arrays.asList("(#100) Hello", null, "Male"), data);
        assertEquals("1.0.4", HuffmanAccess.version(hc));
    }

    @Test
    void txtAndCsvGetTheSameIdValidationAsXml() throws IOException {
        Path txt = TestSupport.writeUtf8(dir.resolve("dup.txt"), "# TLKToolVersion=1.0.4\n5: one\n6: x\n5: two\n5: three\n");
        Path csv = TestSupport.writeUtf8(dir.resolve("dup.csv"), "TLKToolVersion,1.0.4\nid,position,hasText,text\n"
                + "5,0,true,one\n6,1,true,x\n5,2,true,two\n5,3,false,\n");
        for (Object[] input : new Object[][]{{txt, FileFormat.TXT}, {csv, FileFormat.CSV}}) {
            HuffmanCompression hc = new HuffmanCompression();
            IOException e = assertThrows(IOException.class,
                    () -> hc.loadInputData(input[0].toString(), (FileFormat) input[1], false));
            assertEquals("String id 5 occurs 3 times (positions [0, 2, 3]), but a TLK file can only hold a male and a female"
                    + " variant of a string: " + input[0], e.getMessage());
            assertTrue(HuffmanAccess.entries(hc).isEmpty());
            assertEquals(HuffmanCompression.DEFAULT_TOOL_VERSION, HuffmanAccess.version(hc));
        }
    }

    @Test
    void twoOccurrencesBecomeMaleAndFemaleEntries() throws IOException {
        Path csv = TestSupport.writeUtf8(dir.resolve("two.csv"), "TLKToolVersion,1.0.4\nid,position,hasText,text\n"
                + "5,3,true,she\n6,1,true,x\n5,0,true,he\n");
        TlkFile t = reencode(csv, FileFormat.CSV);
        assertEquals(Arrays.asList("sections 2/1", "0: 5 false he", "1: 6 false x", "2: 5 false she"), describe(t));
    }

    @Test
    void sameFileInAllThreeFormatsGivesTheSameTlk() throws IOException {
        TlkFile original = load(tlk(trickyFile()));
        byte[] fromXml = tlkBytes(export(original, FileFormat.XML), FileFormat.XML);
        assertArrayEquals(fromXml, tlkBytes(export(original, FileFormat.TXT), FileFormat.TXT));
        assertArrayEquals(fromXml, tlkBytes(export(original, FileFormat.CSV), FileFormat.CSV));
    }

    private byte[] tlkBytes(Path text, FileFormat format) throws IOException {
        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(text.toString(), format, false);
        Path tlk = dir.resolve("bytes" + (fileCounter++) + ".tlk");
        hc.saveToTlkFile(tlk.toString(), null);
        return Files.readAllBytes(tlk);
    }
}
