package ru;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitConverterTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void canBeInstantiated() {
        assertNotNull(new BitConverter());
    }

    @Nested
    class GetBytes {

        @Test
        void booleanIsOneByte() {
            assertArrayEquals(bytes(1), BitConverter.getBytes(true));
            assertArrayEquals(bytes(0), BitConverter.getBytes(false));
        }

        @Test
        void charIsBigEndianLikeToChar() {
            assertArrayEquals(bytes(0x00, 0x41), BitConverter.getBytes('A'));
            assertArrayEquals(bytes(0x04, 0x16), BitConverter.getBytes('Ж'));
            assertArrayEquals(bytes(0x00, 0x00), BitConverter.getBytes('\0'));
            assertArrayEquals(bytes(0xff, 0xff), BitConverter.getBytes(Character.MAX_VALUE));
            for (char c : new char[]{'A', 'Ж', '\0', '\uffff', '\u8001'}) {
                assertEquals(c, BitConverter.toChar(BitConverter.getBytes(c), 0));
            }
        }

        @Test
        void shortIsBigEndian() {
            assertArrayEquals(bytes(0x12, 0x34), BitConverter.getBytes((short) 0x1234));
            assertArrayEquals(bytes(0x00, 0x00), BitConverter.getBytes((short) 0));
            assertArrayEquals(bytes(0xff, 0xff), BitConverter.getBytes((short) -1));
            assertArrayEquals(bytes(0x80, 0x00), BitConverter.getBytes(Short.MIN_VALUE));
            assertArrayEquals(bytes(0x7f, 0xff), BitConverter.getBytes(Short.MAX_VALUE));
        }

        @Test
        void intIsBigEndian() {
            assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04), BitConverter.getBytes(0x01020304));
            assertArrayEquals(bytes(0, 0, 0, 0), BitConverter.getBytes(0));
            assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff), BitConverter.getBytes(-1));
            assertArrayEquals(bytes(0x80, 0, 0, 0), BitConverter.getBytes(Integer.MIN_VALUE));
            assertArrayEquals(bytes(0x7f, 0xff, 0xff, 0xff), BitConverter.getBytes(Integer.MAX_VALUE));
        }

        @Test
        void longIsBigEndian() {
            assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8), BitConverter.getBytes(0x0102030405060708L));
            assertArrayEquals(new byte[8], BitConverter.getBytes(0L));
            assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), BitConverter.getBytes(-1L));
            assertArrayEquals(bytes(0x80, 0, 0, 0, 0, 0, 0, 0), BitConverter.getBytes(Long.MIN_VALUE));
            assertArrayEquals(bytes(0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), BitConverter.getBytes(Long.MAX_VALUE));
        }

        @Test
        void floatIsRawIeeeBitsBigEndian() {
            assertArrayEquals(bytes(0x3f, 0x80, 0, 0), BitConverter.getBytes(1.0f));
            assertArrayEquals(bytes(0x80, 0, 0, 0), BitConverter.getBytes(-0.0f));
            // raw bits: a NaN payload is preserved, not canonicalised
            assertArrayEquals(bytes(0x7f, 0xc0, 0x00, 0x01), BitConverter.getBytes(Float.intBitsToFloat(0x7fc00001)));
        }

        @Test
        void doubleIsRawIeeeBitsBigEndian() {
            assertArrayEquals(bytes(0x3f, 0xf0, 0, 0, 0, 0, 0, 0), BitConverter.getBytes(1.0));
            assertArrayEquals(bytes(0x80, 0, 0, 0, 0, 0, 0, 0), BitConverter.getBytes(-0.0));
            assertArrayEquals(bytes(0x7f, 0xf0, 0, 0, 0, 0, 0, 0), BitConverter.getBytes(Double.POSITIVE_INFINITY));
        }

        @Test
        void stringIsUtf8() {
            assertArrayEquals(bytes('a', 'b', 'c'), BitConverter.getBytes("abc"));
            assertArrayEquals(new byte[0], BitConverter.getBytes(""));
            assertArrayEquals("Жa".getBytes(StandardCharsets.UTF_8), BitConverter.getBytes("Жa"));
            assertArrayEquals(bytes(0xd0, 0x96, 'a'), BitConverter.getBytes("Жa"));
        }

        @Test
        void littleEndianIntIsLowByteFirstOnEveryPlatform() {
            assertArrayEquals(bytes(0x04, 0x03, 0x02, 0x01), BitConverter.getBytesLittleEndian(0x01020304));
            assertArrayEquals(bytes(0x41, 0x00, 0x01, 0x00), BitConverter.getBytesLittleEndian(0x10041));
            assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff), BitConverter.getBytesLittleEndian(-1));
            assertArrayEquals(bytes(0, 0, 0, 0x80), BitConverter.getBytesLittleEndian(Integer.MIN_VALUE));
        }
    }

    @Nested
    class DoubleBits {

        @Test
        void doubleToInt64BitsReturnsRawBits() {
            assertEquals(0x3ff0000000000000L, BitConverter.doubleToInt64Bits(1.0));
            assertEquals(Long.MIN_VALUE, BitConverter.doubleToInt64Bits(-0.0));
            assertEquals(0L, BitConverter.doubleToInt64Bits(0.0));
            assertEquals(0x7ff8000000000001L, BitConverter.doubleToInt64Bits(Double.longBitsToDouble(0x7ff8000000000001L)));
        }

        @Test
        void int64BitsToDoubleOfZeroIsZero() {
            assertEquals(0.0, BitConverter.int64BitsToDouble(0L), 0.0);
        }

        @Test
        void int64BitsToDoubleIsInverseOfDoubleToInt64Bits() {
            for (double d : new double[]{1.0, -2.5, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE, -0.0}) {
                assertEquals(Double.doubleToRawLongBits(d),
                        Double.doubleToRawLongBits(BitConverter.int64BitsToDouble(BitConverter.doubleToInt64Bits(d))));
            }
            assertEquals(1.0, BitConverter.int64BitsToDouble(0x3ff0000000000000L), 0.0);
        }
    }

    @Nested
    class ToBoolean {

        @Test
        void zeroIsFalseAnythingElseTrue() {
            assertFalse(BitConverter.toBoolean(bytes(0), 0));
            assertTrue(BitConverter.toBoolean(bytes(1), 0));
            assertTrue(BitConverter.toBoolean(bytes(0x80), 0));
            assertTrue(BitConverter.toBoolean(bytes(0xff), 0));
        }

        @Test
        void roundTrip() {
            assertTrue(BitConverter.toBoolean(BitConverter.getBytes(true), 0));
            assertFalse(BitConverter.toBoolean(BitConverter.getBytes(false), 0));
        }

        @Test
        void requiresOneByteAtTheIndex() {
            IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class,
                    () -> BitConverter.toBoolean(new byte[0], 0));
            assertEquals("toBoolean needs 1 byte at index 0, but the array has 0", e.getMessage());
            assertFalse(BitConverter.toBoolean(bytes(0, 1), 0));
            assertTrue(BitConverter.toBoolean(bytes(0, 1), 1));
        }

        @Test
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> BitConverter.toBoolean(null, 0));
        }

        @Test
        void indexOutOfRange() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toBoolean(new byte[1], 1));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toBoolean(new byte[1], -1));
        }
    }

    @Nested
    class ToChar {

        @Test
        void readsBigEndianAtIndex() {
            assertEquals('Ж', BitConverter.toChar(bytes(0x04, 0x16), 0));
            assertEquals('\uffff', BitConverter.toChar(bytes(0xff, 0xff), 0));
            assertEquals('\0', BitConverter.toChar(bytes(0, 0), 0));
            assertEquals('A', BitConverter.toChar(bytes(0x7f, 0x00, 0x41), 1));
        }

        @Test
        void requiresAtLeastTwoBytes() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toChar(new byte[0], 0));
            IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class,
                    () -> BitConverter.toChar(new byte[1], 0));
            assertEquals("toChar needs 2 bytes at index 0, but the array has 1", e.getMessage());
        }

        @Test
        void indexOutOfRange() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toChar(new byte[2], 1));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toChar(new byte[2], -1));
        }
    }

    @Nested
    class ToCharLittleEndian {

        @Test
        void readsLittleEndianCharAtByteIndex() {
            assertEquals('Ж', BitConverter.toCharLittleEndian(bytes(0x16, 0x04), 0));
            assertEquals('ȁ', BitConverter.toCharLittleEndian(bytes(1, 2, 3, 4), 0));
            assertEquals('\u0302', BitConverter.toCharLittleEndian(bytes(1, 2, 3, 4), 1));
            assertEquals('Ѓ', BitConverter.toCharLittleEndian(bytes(1, 2, 3, 4), 2));
            assertEquals('\uffff', BitConverter.toCharLittleEndian(bytes(0xff, 0xff), 0));
        }

        @Test
        void extractsLowSixteenBitsOfALittleEndianInt() {
            // what the C# original does to turn a leaf id back into a character: 0xffff - (-1 - c)
            for (char c : new char[]{'\0', ' ', 'A', 'Ж', '№', '\uffff'}) {
                int leafId = -1 - c;
                assertEquals(c, BitConverter.toCharLittleEndian(BitConverter.getBytesLittleEndian(0xffff - leafId), 0));
                assertEquals(c, TlkFile.leafCharacter(leafId));
            }
        }

        @Test
        void requiresAtLeastTwoBytes() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toCharLittleEndian(new byte[0], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toCharLittleEndian(new byte[1], 0));
        }

        @Test
        void indexOutOfRange() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toCharLittleEndian(new byte[4], 3));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toCharLittleEndian(new byte[4], -1));
        }

        @Test
        void doesNotModifyTheInputArray() {
            byte[] input = bytes(1, 2, 3, 4);
            BitConverter.toCharLittleEndian(input, 0);
            assertArrayEquals(bytes(1, 2, 3, 4), input);
        }

        @Test
        void oddLengthArrayReadsTheFirstPair() {
            assertEquals('ȁ', BitConverter.toCharLittleEndian(bytes(1, 2, 3), 0));
        }
    }

    @Nested
    class ToInt16 {

        @Test
        void rejectsArraysShorterThanTwoBytes() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt16(new byte[0], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt16(new byte[1], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt16(new byte[8], 7));
        }

        @Test
        void readsTwoByteArray() {
            assertEquals((short) 0x1234, BitConverter.toInt16(bytes(0x12, 0x34), 0));
            assertEquals(Short.MIN_VALUE, BitConverter.toInt16(BitConverter.getBytes(Short.MIN_VALUE), 0));
            assertEquals(Short.MAX_VALUE, BitConverter.toInt16(BitConverter.getBytes(Short.MAX_VALUE), 0));
            assertEquals((short) -1, BitConverter.toInt16(BitConverter.getBytes((short) -1), 0));
        }
    }

    @Nested
    class ToInt32 {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, -1, 0x01020304, 0x7f, 0x80, 0xff, Integer.MIN_VALUE, Integer.MAX_VALUE})
        void roundTripsWithGetBytes(int value) {
            assertEquals(value, BitConverter.toInt32(BitConverter.getBytes(value), 0));
        }

        @Test
        void readsBigEndian() {
            assertEquals(0x01020304, BitConverter.toInt32(bytes(1, 2, 3, 4), 0));
            assertEquals(0xff000000, BitConverter.toInt32(bytes(0xff, 0, 0, 0), 0));
        }

        @Test
        void requiresFourBytesFromTheIndex() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt32(new byte[0], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt32(new byte[3], 0));
            assertEquals(0x01020304, BitConverter.toInt32(bytes(1, 2, 3, 4, 5), 0));
            assertEquals(0x02030405, BitConverter.toInt32(bytes(1, 2, 3, 4, 5), 1));
        }

        @Test
        void indexOutOfRange() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt32(new byte[4], 1));
        }
    }

    @Nested
    class ToInt64 {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, -1L, 0x0102030405060708L, Long.MIN_VALUE, Long.MAX_VALUE, 0xffL, 0x80000000L})
        void roundTripsWithGetBytes(long value) {
            assertEquals(value, BitConverter.toInt64(BitConverter.getBytes(value), 0));
        }

        @Test
        void requiresEightBytesFromTheIndex() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt64(new byte[0], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt64(new byte[7], 0));
            assertEquals(0x0203040506070809L, BitConverter.toInt64(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9), 1));
        }

        @Test
        void indexOutOfRange() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toInt64(new byte[8], 1));
        }
    }

    @Nested
    class ToSingleAndDouble {

        @ParameterizedTest
        @ValueSource(floats = {0.0f, -0.0f, 1.5f, -3.25f, Float.MIN_VALUE, Float.MAX_VALUE,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN})
        void singleRoundTripsBitExact(float value) {
            assertEquals(Float.floatToRawIntBits(value),
                    Float.floatToRawIntBits(BitConverter.toSingle(BitConverter.getBytes(value), 0)));
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.0, -0.0, 1.5, Math.PI, -1e300, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN})
        void doubleRoundTripsBitExact(double value) {
            assertEquals(Double.doubleToRawLongBits(value),
                    Double.doubleToRawLongBits(BitConverter.toDouble(BitConverter.getBytes(value), 0)));
        }

        @Test
        void singleRequiresFourBytesFromTheIndex() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toSingle(new byte[3], 0));
            assertEquals(1.0f, BitConverter.toSingle(bytes(0, 0x3f, 0x80, 0, 0), 1), 0.0f);
        }

        @Test
        void doubleRequiresEightBytesFromTheIndex() {
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toDouble(new byte[4], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> BitConverter.toDouble(new byte[8], 1));
        }
    }

    @Nested
    class ToStringOfBytes {

        @Test
        void decodesUtf8() {
            assertEquals("abc", BitConverter.toString(bytes('a', 'b', 'c')));
            assertEquals("", BitConverter.toString(new byte[0]));
            assertEquals("Жa", BitConverter.toString(bytes(0xd0, 0x96, 'a')));
        }

        @Test
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> BitConverter.toString(null));
        }
    }
}
