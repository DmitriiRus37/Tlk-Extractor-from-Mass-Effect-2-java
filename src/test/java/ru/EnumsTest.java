package ru;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumsTest {

    @Test
    void fileFormatConstants() {
        assertArrayEquals(new FileFormat[]{FileFormat.TXT, FileFormat.CSV, FileFormat.XML}, FileFormat.values());
        for (FileFormat f : FileFormat.values()) {
            assertSame(f, FileFormat.valueOf(f.name()));
        }
        assertEquals("XML", FileFormat.XML.toString());
    }

    @Test
    void extensions() {
        assertEquals("txt", FileFormat.TXT.getExtension());
        assertEquals("csv", FileFormat.CSV.getExtension());
        assertEquals("xml", FileFormat.XML.getExtension());
    }

    @Test
    void formatFromFileName() {
        assertSame(FileFormat.XML, FileFormat.fromFileName("BIOGame_RUS.xml"));
        assertSame(FileFormat.CSV, FileFormat.fromFileName("C:\\Games\\BIOGame_RUS.CSV "));
        assertSame(FileFormat.TXT, FileFormat.fromFileName("/tmp/a.b/Strings.Txt"));
        assertNull(FileFormat.fromFileName("BIOGame_RUS.tlk"));
        assertNull(FileFormat.fromFileName("xml"));
        assertNull(FileFormat.fromFileName("archive.xml.gz"));
        assertNull(FileFormat.fromFileName(""));
        assertNull(FileFormat.fromFileName(null));
    }

    @Test
    void unknownNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FileFormat.valueOf("xml"));
        assertThrows(NullPointerException.class, () -> FileFormat.valueOf(null));
    }
}
