package ru;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that {@link FastXml} returns exactly what the StAX reader returns for every file it
 * accepts, accepts what {@link XmlFormat} writes, and leaves everything else to StAX.
 */
class FastXmlTest {

    private static final Path FILE = Path.of("fuzz.xml");

    private static List<String> describe(InputFile input) {
        List<String> result = new ArrayList<>();
        result.add(input.toolVersion());
        for (TlkEntry e : input.entries()) {
            result.add(e.stringId() + "|" + e.position() + "|" + (e.data() == null ? "<null>" : "[" + e.data() + "]"));
        }
        return result;
    }

    /** What XmlFormat writes for the given texts (null = no text), ids 0, 1, ... or negative for no text. */
    private static String export(List<String> texts) throws IOException {
        StringWriter out = new StringWriter();
        FormatWriter writer = XmlFormat.writer(out);
        writer.begin(TlkFile.TOOL_VERSION);
        writer.comment("Male entries section begin (ends at position " + (texts.size() - 1) + ")");
        for (int i = 0; i < texts.size(); i++) {
            TlkFile.TlkStringRef ref = new TlkFile.TlkStringRef(texts.get(i) == null ? -1 - i : i, 0);
            ref.position = i;
            ref.data = texts.get(i);
            writer.entry(ref);
            if (i == texts.size() / 2) {
                writer.comment("Male entries section end");
            }
        }
        writer.comment("Female entries section end");
        writer.end();
        return out.toString();
    }

    private static List<String> trickyTexts() {
        List<String> texts = new ArrayList<>();
        for (String t : new String[]{"plain", "", " lead and trail ", "a & b < c > d", "]]", "x]]y", "line\nfeed",
                                     "cr\rlf\r\n", "tab\there", "quote \" and '", "Жёлтый ёж", "emoji 😀",
                                     "\u0085  ctrl-ish", "-1", "123", "&amp; literal", "<string>", null, null,
                                     "�", "-->", "<!--"}) {
            texts.add(t);
        }
        return texts;
    }

    /** Asserts that FastXml accepts the bytes and agrees with StAX. */
    private static void assertSame(byte[] bytes) throws IOException {
        InputFile fast = FastXml.read(bytes);
        assertNotNull(fast, "FastXml gave up on a file XmlFormat wrote");
        assertEquals(describe(XmlFormat.readWithStax(bytes, FILE)), describe(fast));
    }

    @Test
    void readsTheSampleExportLikeStax() throws Exception {
        assertSame(Files.readAllBytes(SampleFixture.xml()));
    }

    @Test
    void readsWhatXmlFormatWritesLikeStax() throws IOException {
        assertSame(export(trickyTexts()).getBytes(StandardCharsets.UTF_8));
        assertSame(export(List.of()).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leavesOtherLayoutsToStax() throws IOException {
        String canonical = export(List.of("a", "b"));
        String[] variants = {
            "﻿" + canonical,                                             // byte order mark
            canonical.replace("\n", "\r\n"),                                  // CR line ends
            canonical.replace("<data>a</data>", "<data hasText=\"true\">a</data>"),
            canonical.replace("<data>a</data>", "<data><![CDATA[a]]></data>"),
            canonical.replace("<data>a</data>", "<data>&apos;</data>"),
            canonical.replace("<data>a</data>", "<data>&#65;</data>"),
            canonical.replace("<id>0</id>", "<id>+0</id>"),
            canonical.replace("<id>0</id>", "<id> 0 </id>"),
            canonical.replace("<id>0</id>", "<id>12345678901</id>"),
            canonical.replace("<position>0</position>", "<position>2147483648</position>"),
            canonical.replace("TLKToolVersion=\"1.0.4\"", "TLKToolVersion='1.0.4'"),
            canonical.replace("TLKToolVersion=\"1.0.4\"", "TLKToolVersion=\"1.0.4\" x=\"y\""),
            canonical.replace("encoding=\"UTF-8\"", "encoding=\"utf-8\""),
            canonical.replace("<string>", "<string><!-- c -->"),
            canonical.replace("<data>a</data>", "<data>a]]>b</data>"),
            canonical.replace("<data>a</data>", "<data>a\u0001</data>"),
            canonical + "<?pi?>",
            canonical.replace("Female entries section end", "bad -- comment"),
        };
        for (String variant : variants) {
            assertNull(FastXml.read(variant.getBytes(StandardCharsets.UTF_8)), variant);
        }
        // a negative id without text needs a negative <data>: StAX reports the error
        assertNull(FastXml.read(canonical.replace("<id>1</id>", "<id>-5</id>").getBytes(StandardCharsets.UTF_8)));
        assertNull(FastXml.read(new byte[]{(byte) 0xFF, '<'}));
    }

    @Test
    void neverAcceptsWhatStaxRejectsOrReadsDifferently() throws IOException {
        Random random = new Random(20261017);
        byte[] base = export(trickyTexts()).getBytes(StandardCharsets.UTF_8);
        String[] pieces = {"<", ">", "&", ";", "#", "1", "3", "-", "!", "/", "\"", "'", "=", " ", "\n", "\r", "\t",
                           "]", "[", "x", "a", "ё", "﻿", "\u0001", "￾", "&amp;", "&lt;", "&#13;",
                           "&#xD;", "]]>", "<!--", "-->", "<![CDATA[", "<string>", "</string>", "<id>", "</id>",
                           "<data>", "</data>", "<data hasText=\"false\">", "-1", "007", "+"};
        int accepted = 0;
        int acceptedChanged = 0;
        for (int round = 0; round < 30_000; round++) {
            byte[] bytes = base;
            for (int m = 1 + random.nextInt(3); m > 0; m--) {
                bytes = mutate(bytes, random, pieces);
            }
            InputFile fast = FastXml.read(bytes);
            if (fast == null) {
                continue;
            }
            accepted++;
            InputFile stax;
            try {
                stax = XmlFormat.readWithStax(bytes, FILE);
            } catch (IOException e) {
                fail("FastXml accepted a file StAX rejects (" + e.getMessage() + "):\n"
                        + new String(bytes, StandardCharsets.UTF_8));
                return;
            }
            assertEquals(describe(stax), describe(fast), new String(bytes, StandardCharsets.UTF_8));
            if (!java.util.Arrays.equals(bytes, base)) {
                acceptedChanged++;
            }
        }
        // the fuzzing must also produce changed files that are still accepted, or it tests little
        assertTrue(acceptedChanged > 1000, accepted + " accepted, " + acceptedChanged + " of them changed");
    }

    private static byte[] mutate(byte[] bytes, Random random, String[] pieces) {
        int at = random.nextInt(bytes.length + 1);
        byte[] insert = random.nextInt(20) == 0 ? new byte[]{(byte) (0x80 + random.nextInt(0x80))}
                : pieces[random.nextInt(pieces.length)].getBytes(StandardCharsets.UTF_8);
        int remove = switch (random.nextInt(3)) {
            case 0 -> 0;                                            // insert
            case 1 -> Math.min(bytes.length - at, 1 + random.nextInt(4)); // replace
            default -> -1;                                          // delete
        };
        if (remove < 0) {
            int length = Math.min(bytes.length - at, 1 + random.nextInt(4));
            byte[] result = new byte[bytes.length - length];
            System.arraycopy(bytes, 0, result, 0, at);
            System.arraycopy(bytes, at + length, result, at, bytes.length - at - length);
            return result;
        }
        byte[] result = new byte[bytes.length - remove + insert.length];
        System.arraycopy(bytes, 0, result, 0, at);
        System.arraycopy(insert, 0, result, at, insert.length);
        System.arraycopy(bytes, at + remove, result, at + insert.length, bytes.length - at - remove);
        return result;
    }
}
