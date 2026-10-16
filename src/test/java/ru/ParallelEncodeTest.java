package ru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks {@link HuffmanCompression#saveToTlkFile} with more distinct texts than it encodes per
 * parallel task, so that chunks meet in the middle of bytes.
 */
class ParallelEncodeTest {

    @TempDir
    Path dir;

    @Test
    void manyChunksDecodeToTheSameTexts() throws IOException {
        Random random = new Random(3);
        List<String> texts = new ArrayList<>();
        StringBuilder txt = new StringBuilder("# TLKToolVersion=1.0.4\r\n");
        for (int i = 0; i < 4000; i++) {
            String text;
            if (i % 5 == 4) {
                // a repeated text shares the offset of its first occurrence
                text = texts.get(random.nextInt(texts.size()));
            } else {
                StringBuilder sb = new StringBuilder();
                for (int k = random.nextInt(40); k > 0; k--) {
                    sb.append((char) (random.nextInt(4) == 0 ? 'а' + random.nextInt(32) : 'a' + random.nextInt(26)));
                }
                text = sb.append(' ').append(i).toString();
            }
            texts.add(text);
            txt.append(i).append(": ").append(TxtFormat.escape(text)).append("\r\n");
        }
        Path input = TestSupport.writeUtf8(dir.resolve("many.txt"), txt.toString());
        Path tlk = dir.resolve("many.tlk");

        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(input.toString(), FileFormat.TXT, false);
        hc.saveToTlkFile(tlk.toString(), null);

        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        List<String> decoded = new ArrayList<>();
        for (TlkFile.TlkStringRef ref : TlkFileTest.refs(t)) {
            decoded.add(ref.data);
        }
        assertEquals(texts, decoded);
    }
}
