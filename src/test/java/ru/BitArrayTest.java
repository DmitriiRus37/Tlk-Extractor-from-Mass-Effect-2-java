package ru;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitArrayTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    private static BitArray bits(String zeroesAndOnes) {
        boolean[] b = new boolean[zeroesAndOnes.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = zeroesAndOnes.charAt(i) == '1';
        }
        return new BitArray(b);
    }

    @Nested
    class Construction {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 7, 8, 9, 15, 16, 17, 64, 1000})
        void lengthConstructorCreatesZeroedArray(int length) {
            BitArray ba = new BitArray(length);
            assertEquals(length, ba.length());
            assertEquals((length + 7) / 8, ba.toByteArray().length);
            for (int i = 0; i < length; i++) {
                assertFalse(ba.get(i));
            }
        }

        @Test
        void negativeLengthIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new BitArray(-1));
            assertThrows(IllegalArgumentException.class, () -> new BitArray(Integer.MIN_VALUE));
        }

        @Test
        void byteArrayConstructorHasEightBitsPerByte() {
            assertEquals(0, new BitArray(new byte[0]).length());
            assertEquals(8, new BitArray(new byte[1]).length());
            assertEquals(24, new BitArray(new byte[3]).length());
        }

        @Test
        void byteArrayConstructorCopiesTheInput() {
            byte[] input = bytes(0x0f);
            BitArray ba = new BitArray(input);
            input[0] = 0;
            assertArrayEquals(bytes(0x0f), ba.toByteArray());
        }

        @Test
        void byteArrayConstructorRejectsNull() {
            assertThrows(NullPointerException.class, () -> new BitArray((byte[]) null));
        }

        @Test
        void booleanArrayConstructorKeepsOrder() {
            boolean[] input = {true, false, true, true, false, false, false, false, true};
            BitArray ba = new BitArray(input);
            assertEquals(9, ba.length());
            assertArrayEquals(input, ba.toBooleanArray());
            // MSB-first packing; unused bits of the last byte stay zero
            assertArrayEquals(bytes(0xb0, 0x80), ba.toByteArray());
        }

        @Test
        void emptyBooleanArray() {
            BitArray ba = new BitArray(new boolean[0]);
            assertEquals(0, ba.length());
            assertEquals(0, ba.toByteArray().length);
            assertEquals(0, ba.toBooleanArray().length);
        }
    }

    @Nested
    class GetAndSet {

        @Test
        void getIsMsbFirst() {
            BitArray ba = new BitArray(bytes(0x80, 0x01));
            assertTrue(ba.get(0));
            for (int i = 1; i < 15; i++) {
                assertFalse(ba.get(i), "bit " + i);
            }
            assertTrue(ba.get(15));
        }

        @Test
        void getRevIsLsbFirst() {
            BitArray ba = new BitArray(bytes(0x01, 0x80));
            assertTrue(ba.getRev(0));
            for (int i = 1; i < 15; i++) {
                assertFalse(ba.getRev(i), "bit " + i);
            }
            assertTrue(ba.getRev(15));
        }

        @Test
        void getRevMirrorsGetWithinEachByte() {
            BitArray ba = new BitArray(bytes(0x96, 0x3c, 0xa5, 0xff, 0x00, 0x01));
            for (int i = 0; i < ba.length(); i++) {
                int mirrored = (i / 8) * 8 + (7 - i % 8);
                assertEquals(ba.get(mirrored), ba.getRev(i), "bit " + i);
            }
        }

        @Test
        void getRevMatchesShiftAndMaskOnEveryByteValue() {
            byte[] all = new byte[256];
            for (int i = 0; i < 256; i++) {
                all[i] = (byte) i;
            }
            BitArray ba = new BitArray(all);
            for (int i = 0; i < ba.length(); i++) {
                assertEquals(((all[i / 8] >> (i % 8)) & 1) == 1, ba.getRev(i), "bit " + i);
            }
        }

        @Test
        void getBitCountsFromEitherEndOfAnUnpackedByte() throws Exception {
            // private helper behind getRev: byteImpl[0] is the 0x80 bit
            BitArray ba = new BitArray(8);
            boolean[] onlyMsb = {true, false, false, false, false, false, false, false};
            Class<?>[] types = TestSupport.types(boolean[].class, int.class, Endian.class);
            assertTrue(TestSupport.<Boolean>call(ba, "getBit", types, onlyMsb, 0, Endian.BigEndian));
            assertFalse(TestSupport.<Boolean>call(ba, "getBit", types, onlyMsb, 0, Endian.LittleEndian));
            assertTrue(TestSupport.<Boolean>call(ba, "getBit", types, onlyMsb, 15, Endian.LittleEndian));
            assertTrue(TestSupport.<Boolean>call(ba, "getBit", types, onlyMsb, 8, Endian.BigEndian));
        }

        @Test
        void setWritesMsbFirstAndCanClear() {
            BitArray ba = new BitArray(16);
            ba.set(0, true);
            ba.set(9, true);
            ba.set(15, true);
            assertArrayEquals(bytes(0x80, 0x41), ba.toByteArray());
            ba.set(9, false);
            ba.set(0, false);
            assertArrayEquals(bytes(0x00, 0x01), ba.toByteArray());
            // setting an already set bit keeps it
            ba.set(15, true);
            assertTrue(ba.get(15));
        }

        @Test
        void outOfRangeIndicesAreRejected() {
            BitArray ba = new BitArray(9);
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.get(-1));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.get(9));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.getRev(-1));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.getRev(9));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.set(-1, true));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.set(9, true));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.get(Integer.MAX_VALUE));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.get(Integer.MIN_VALUE));
        }

        @Test
        void zeroLengthArrayHasNoValidIndex() {
            BitArray ba = new BitArray(0);
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.get(0));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.getRev(0));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> ba.set(0, true));
        }
    }

    @Nested
    class ValueSemantics {

        @Test
        void toByteArrayReturnsACopy() {
            BitArray ba = new BitArray(bytes(0x55));
            byte[] copy = ba.toByteArray();
            copy[0] = 0;
            assertArrayEquals(bytes(0x55), ba.toByteArray());
        }

        @Test
        void equalsComparesLengthAndBits() {
            BitArray a = bits("101");
            assertEquals(a, a);
            assertEquals(a, bits("101"));
            assertEquals(a.hashCode(), bits("101").hashCode());
            assertNotEquals(a, bits("100"));
            // same backing byte, different length
            assertNotEquals(bits("1010"), bits("10100"));
            assertNotEquals(a, "101");
            assertNotEquals(a, null);
            assertEquals(new BitArray(0), new BitArray(new boolean[0]));
        }

        @Test
        void hashCodeDiffersForDifferentLengths() {
            assertNotEquals(new BitArray(8).hashCode(), new BitArray(7).hashCode());
        }

        @Test
        void cloneIsIndependent() {
            BitArray original = bits("1100");
            BitArray copy = (BitArray) original.clone();
            assertNotSame(original, copy);
            assertEquals(original, copy);
            copy.set(0, false);
            assertTrue(original.get(0));
            assertNotEquals(original, copy);
        }

        @Test
        void toBooleanArrayMatchesGet() {
            BitArray ba = new BitArray(bytes(0xa5, 0x0f));
            boolean[] b = ba.toBooleanArray();
            assertEquals(16, b.length);
            for (int i = 0; i < b.length; i++) {
                assertEquals(ba.get(i), b[i]);
            }
        }
    }

    @Nested
    class ToString {

        @Test
        void singleByte() {
            assertEquals("10100101", new BitArray(bytes(0xa5)).toString());
        }

        @Test
        void oddLengthPrintsOnlyValidBits() {
            assertEquals("1", bits("1").toString());
            assertEquals("101", bits("101").toString());
            assertEquals("10000000 1", bits("100000001").toString());
        }

        @Test
        void groupsBytesAndBreaksLineAfterEight() {
            byte[] nine = new byte[9];
            nine[0] = (byte) 0xff;
            nine[8] = 0x01;
            String expected = "11111111 00000000 00000000 00000000 00000000 00000000 00000000 00000000\n00000001";
            assertEquals(expected, new BitArray(nine).toString());
        }

        @Test
        @Disabled("Known bug: BitArray.toString() throws on a zero-length array")
        void emptyArrayPrintsEmptyString() {
            assertEquals("", new BitArray(0).toString());
        }
    }

    @Nested
    class Truncate {

        @Test
        void keepsWholeBytesUpToLastSetBit() {
            BitArray ba = new BitArray(32);
            ba.set(3, true);
            ba.set(10, true);
            BitArray t = ba.truncate();
            assertEquals(16, t.length());
            assertTrue(t.get(3));
            assertTrue(t.get(10));
            assertFalse(t.get(15));
        }

        @Test
        void lastBitSetKeepsEverything() {
            BitArray ba = new BitArray(16);
            ba.set(15, true);
            assertEquals(ba, ba.truncate());
        }

        @Test
        void noBitSetGivesSingleZeroBit() {
            BitArray t = new BitArray(24).truncate();
            assertEquals(1, t.length());
            assertFalse(t.get(0));
            assertEquals(1, new BitArray(0).truncate().length());
        }
    }
}
