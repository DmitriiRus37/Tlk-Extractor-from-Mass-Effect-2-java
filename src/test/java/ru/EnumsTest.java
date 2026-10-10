package ru;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumsTest {

    @Test
    void endianConstants() {
        assertArrayEquals(new Endian[]{Endian.BigEndian, Endian.LittleEndian}, Endian.values());
        assertSame(Endian.BigEndian, Endian.valueOf("BigEndian"));
        assertSame(Endian.LittleEndian, Endian.valueOf("LittleEndian"));
        assertEquals(0, Endian.BigEndian.ordinal());
        assertEquals(1, Endian.LittleEndian.ordinal());
    }

    @Test
    void fileFormatConstants() {
        assertArrayEquals(new FileFormat[]{FileFormat.TXT, FileFormat.CSV, FileFormat.XML}, FileFormat.values());
        for (FileFormat f : FileFormat.values()) {
            assertSame(f, FileFormat.valueOf(f.name()));
        }
        assertEquals("XML", FileFormat.XML.toString());
    }

    @Test
    void unknownNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Endian.valueOf("MiddleEndian"));
        assertThrows(IllegalArgumentException.class, () -> FileFormat.valueOf("xml"));
        assertThrows(NullPointerException.class, () -> FileFormat.valueOf(null));
    }
}
