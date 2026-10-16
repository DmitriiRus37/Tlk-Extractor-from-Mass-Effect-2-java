package ru;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void unknownNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FileFormat.valueOf("xml"));
        assertThrows(NullPointerException.class, () -> FileFormat.valueOf(null));
    }
}
