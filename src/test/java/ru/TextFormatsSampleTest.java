package ru;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real {@code files/BIOGame_RUS.tlk} through every format: TLK -> TXT/CSV/XML -> TLK -> XML
 * must give exactly the XML exported from the original file.
 */
class TextFormatsSampleTest {

    @ParameterizedTest
    @EnumSource(FileFormat.class)
    void sampleRoundTripGivesTheSameXml(FileFormat format, @TempDir Path dir) throws Exception {
        Path text = dir.resolve("sample." + format.getExtension());
        SampleFixture.tlk().storeToFile(text.toString(), format, null);

        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(text.toString(), format, false);
        assertEquals(SampleFixture.REF_COUNT, HuffmanAccess.entries(hc).size());
        Path tlk = dir.resolve("sample.tlk");
        hc.saveToTlkFile(tlk.toString(), null);

        TlkFile again = new TlkFile();
        again.loadTlkData(tlk.toString());
        assertEquals(SampleFixture.MALE_COUNT, TlkFileTest.header(again).entry1Count);
        assertEquals(SampleFixture.FEMALE_COUNT, TlkFileTest.header(again).entry2Count);
        Path xml = dir.resolve("again.xml");
        again.storeToFile(xml.toString(), FileFormat.XML, null);
        assertArrayEquals(Files.readAllBytes(SampleFixture.xml()), Files.readAllBytes(xml));

        // and the re-encoded file exports to the same text file again
        Path textAgain = dir.resolve("again." + format.getExtension());
        again.storeToFile(textAgain.toString(), format, null);
        assertArrayEquals(Files.readAllBytes(text), Files.readAllBytes(textAgain));
        assertTrue(Files.size(text) > 0);
    }
}
