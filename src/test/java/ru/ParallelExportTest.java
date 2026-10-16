package ru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@link TlkFile#storeToFile} writes exactly what a sequential export writes, also
 * when the file has several of the chunks it formats in parallel and the female section starts
 * at or near a chunk boundary.
 */
class ParallelExportTest {

    /** Entries per chunk, as in {@code TlkFile.EXPORT_CHUNK}. */
    private static final int CHUNK = 2048;
    private static final int TOTAL = 2 * CHUNK + 900;

    @TempDir
    Path dir;

    private TlkFile load(int maleCount, String specialText, int... specialIndices) throws IOException {
        TlkTestFile file = new TlkTestFile();
        for (int i = 0; i < TOTAL; i++) {
            String text = "s" + i + (i % 7 == 0 ? " & <\"q\",\r\n" : "");
            for (int special : specialIndices) {
                if (i == special) {
                    text = specialText;
                }
            }
            int id = i < maleCount ? i : i - maleCount;
            if (i < maleCount) {
                file.male(id, text);
            } else {
                file.female(id, text);
            }
        }
        Path tlk = file.writeTo(dir.resolve("chunks.tlk"));
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        return t;
    }

    /** What the export wrote before it was parallel. */
    private static String sequentialExport(TlkFile t, FileFormat format) throws IOException {
        StringWriter out = new StringWriter();
        FormatWriter writer = format == FileFormat.XML ? XmlFormat.writer(out)
                : format == FileFormat.TXT ? TxtFormat.writer(out) : CsvFormat.writer(out);
        TlkFile.TlkHeader header = TlkFileTest.header(t);
        List<TlkFile.TlkStringRef> refs = TlkFileTest.refs(t);
        int maleCount = header.entry1Count;
        int lastPosition = header.entry1Count + header.entry2Count - 1;
        writer.begin(TlkFile.TOOL_VERSION);
        writer.comment("Male entries section begin (ends at position " + (maleCount - 1) + ")");
        int index = 0;
        for (TlkFile.TlkStringRef ref : refs) {
            if (index++ == maleCount) {
                writer.comment("Male entries section end");
                writer.comment("Female entries section begin (ends at position " + lastPosition + ")");
            }
            writer.entry(ref);
        }
        if (index <= maleCount) {
            writer.comment("Male entries section end");
            writer.comment("Female entries section begin (ends at position " + lastPosition + ")");
        }
        writer.comment("Female entries section end");
        writer.end();
        return out.toString();
    }

    @Test
    void sameOutputAsSequentialAroundChunkBoundaries() throws IOException {
        for (int maleCount : new int[]{0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK, TOTAL - 1, TOTAL}) {
            TlkFile t = load(maleCount, null);
            for (FileFormat format : FileFormat.values()) {
                Path out = dir.resolve("out." + format);
                t.storeToFile(out.toString(), format, null);
                assertEquals(sequentialExport(t, format), TestSupport.readUtf8(out), format + ", " + maleCount + " male");
            }
        }
    }

    @Test
    void firstBadStringInFileOrderIsReported() throws IOException {
        // bad strings in the third and the second chunk: the one in the second is reported
        TlkFile t = load(TOTAL, "bell\u0001", 2 * CHUNK + 5, CHUNK + 10);
        IOException e = assertThrows(IOException.class,
                () -> t.storeToFile(dir.resolve("bad.xml").toString(), FileFormat.XML, null));
        assertTrue(e.getMessage().startsWith("String " + (CHUNK + 10) + " (position " + (CHUNK + 10) + ")"),
                e.getMessage());
    }
}
