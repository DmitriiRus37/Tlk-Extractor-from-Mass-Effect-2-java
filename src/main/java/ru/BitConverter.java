package ru;

import java.lang.Exception;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Partial Java port of .NET's {@code System.BitConverter}: converts primitives to and from byte
 * arrays.
 *
 * <p>Unlike .NET, which uses the machine's byte order (little-endian on PCs), most methods here
 * are big-endian: the {@code getBytes(short/int/long/float/double)} overloads emit the most
 * significant byte first, and {@code toChar}, {@code toInt16}, {@code toInt32}, {@code toInt64},
 * {@code toSingle} and {@code toDouble} read the first byte as the most significant one. The
 * exceptions are {@link #getBytes(char)} (little-endian), {@link #GetBytes(int)} (native order)
 * and {@link #toCharRev(byte[], int)} (little-endian).
 *
 * <p>The {@code to*} methods validate the array length (mostly requiring an <em>exact</em>
 * length, despite the "at least" wording of their messages) but not {@code index}, and report a
 * bad length with a plain checked {@link Exception}.
 *
 * <p>Only {@link #GetBytes(int)} and {@link #toCharRev(byte[], int)} are used in this project, by
 * {@link TlkFile} to turn a Huffman leaf id back into a character.
 *
 * @author kiwimoosical
 * @see <a href="https://www.se7ensins.com/forums/threads/java-c-s-bitconverter-in-java.486271/">
 *      Original "C#'s BitConverter in Java" post</a>
 */
public class BitConverter {

    /**
     * Not meant to be instantiated, as every method is static; kept public like the implicit
     * default constructor it replaces.
     */
    public BitConverter() {
    }

    /**
     * Converts a boolean to one byte.
     *
     * @param x the value
     * @return {@code {1}} for {@code true}, {@code {0}} for {@code false}
     */
    public static byte[] getBytes(boolean x) {
        return new byte[]{(byte) (x ? 1:0)};
    }

    /**
     * Converts a char to two bytes, <em>little-endian</em> (low byte first). This differs from
     * the other {@code getBytes} overloads and from {@link #toChar(byte[], int)}, which is
     * big-endian, so the two are not inverses of each other.
     *
     * @param c the character
     * @return {@code {low byte, high byte}}
     */
    public static byte[] getBytes(char c) {
        return new byte[]  {
                (byte)(c & 0xff),
                (byte)(c >> 8 & 0xff) };
    }

    /**
     * Converts a double to the 8 bytes of its IEEE 754 representation, big-endian.
     *
     * @param x the value
     * @return the raw bits of {@code x} (NaN payloads preserved), most significant byte first
     */
    public static byte[] getBytes(double x) {
        return getBytes(Double.doubleToRawLongBits(x));
    }

    /**
     * Converts a short to two bytes, big-endian.
     *
     * @param x the value
     * @return {@code {high byte, low byte}}
     */
    public static byte[] getBytes(short x) {
        return new byte[] {
                (byte)(x >>> 8),
                (byte)x
        };
    }

    /**
     * Converts an int to four bytes, big-endian. See {@link #GetBytes(int)} for the native-order
     * variant.
     *
     * @param x the value
     * @return the bytes of {@code x}, most significant first
     */
    public static byte[] getBytes(int x) {
        return new byte[] {
                (byte)(x >>> 24),
                (byte)(x >>> 16),
                (byte)(x >>> 8),
                (byte)x
        };
    }

    /**
     * Converts a long to eight bytes, big-endian.
     *
     * @param x the value
     * @return the bytes of {@code x}, most significant first
     */
    public static byte[] getBytes(long x) {
        return new byte[] {
                (byte)(x >>> 56),
                (byte)(x >>> 48),
                (byte)(x >>> 40),
                (byte)(x >>> 32),
                (byte)(x >>> 24),
                (byte)(x >>> 16),
                (byte)(x >>> 8),
                (byte)x
        };
    }

    /**
     * Converts a float to the 4 bytes of its IEEE 754 representation, big-endian.
     *
     * @param x the value
     * @return the raw bits of {@code x}, most significant byte first
     */
    public static byte[] getBytes(float x) {
        return getBytes(Float.floatToRawIntBits(x));
    }

    /**
     * Encodes a string with the platform default charset (UTF-8 on JDK 18 and later, unless
     * overridden with {@code -Dfile.encoding}).
     *
     * @param x the string
     * @return the encoded bytes
     */
    public static byte[] getBytes(String x) {
        return x.getBytes();
    }

    /**
     * Returns the raw IEEE 754 bit pattern of a double, like .NET's
     * {@code BitConverter.DoubleToInt64Bits}.
     *
     * @param x the value
     * @return the bits of {@code x}
     */
    public static long doubleToInt64Bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    /**
     * Converts a long to a double.
     *
     * <p>Note: this is a numeric conversion ({@code (double) x}), not a reinterpretation of the
     * bits as .NET's {@code BitConverter.Int64BitsToDouble} does, so it is not the inverse of
     * {@link #doubleToInt64Bits(double)}; {@link Double#longBitsToDouble(long)} would be.
     *
     * @param x the value
     * @return {@code x} converted to the nearest double
     */
    public static double int64BitsToDouble(long x) {
        return (double)x;
    }

    /**
     * Reads a boolean from a one-byte array.
     *
     * @param bytes the array; must have exactly 1 element
     * @param index position of the byte; only {@code 0} is valid
     * @return {@code true} if the byte is non-zero
     * @throws Exception if {@code bytes.length != 1}
     */
    public static boolean toBoolean(byte[] bytes, int index) throws Exception {
        if (bytes.length != 1)
            throw new Exception("The length of the byte array must be at least 1 byte long.");
        return bytes[index] != 0;
    }

    /**
     * Reads a char from two bytes, big-endian.
     *
     * @param bytes the array; must have at least 2 elements
     * @param index position of the high byte; {@code index + 1} must also be valid
     * @return {@code (bytes[index] << 8) | bytes[index + 1]} as an unsigned 16-bit char
     * @throws Exception if {@code bytes.length < 2}
     */
    public static char toChar(byte[] bytes, int index) throws Exception {
        if (bytes.length < 2) {
            throw new Exception("The length of the byte array must be at least 2 bytes long.");
        }
        return (char) (((0xff & bytes[index]) << 8) | (0xff & bytes[index + 1]));
    }

    /**
     * Reads a little-endian char, i.e. the equivalent of .NET's {@code BitConverter.ToChar} on a
     * little-endian machine, except that {@code index} counts chars rather than bytes.
     *
     * <p>For an even-length array the result is
     * {@code (bytes[2 * index + 1] << 8) | bytes[2 * index]}. {@link TlkFile} calls it with the
     * output of {@link #GetBytes(int)} and {@code index} 0 to get the low 16 bits of an int.
     *
     * <p>Note: it reverses {@code bytes} in place as a side effect, and for odd-length arrays the
     * byte pairs are misaligned.
     *
     * @param bytes the array; must have at least 2 elements, and is modified
     * @param index zero-based char (2-byte) index from the start of the array
     * @return the decoded char
     * @throws Exception if {@code bytes.length < 2}
     */
    public static char toCharRev(byte[] bytes, int index) throws Exception {
        if (bytes.length < 2) {
            throw new Exception("The length of the byte array must be at least 2 bytes long.");
        }
        char[] buffer = new char[bytes.length >> 1];
        for (int i=0; i< bytes.length/2; i++) {
            byte temp = bytes[i];
            bytes[i] = bytes[bytes.length-1-i];
            bytes[bytes.length-1-i] = temp;
        }
        for (int i = 0; i < buffer.length; i++) {
            int bpos = i << 1;
            char c = (char)(((bytes[bpos]&0x00FF)<<8) + (bytes[bpos+1]&0x00FF));
            buffer[i] = c;
        }
        int countOfChars = buffer.length;
        return buffer[countOfChars-1-index];
    }

    /**
     * Reads a double from its 8-byte IEEE 754 representation, big-endian.
     *
     * @param bytes the array; must have exactly 8 elements
     * @param index position of the first byte; only {@code 0} is valid
     * @return the decoded double
     * @throws Exception if {@code bytes.length != 8}
     */
    public static double toDouble(byte[] bytes, int index) throws Exception {
        if (bytes.length != 8) {
            throw new Exception("The length of the byte array must be at least 8 bytes long.");
        }
        return Double.longBitsToDouble(toInt64(bytes, index));
    }

    /**
     * Reads a short from two bytes, big-endian.
     *
     * <p>Note: the length check requires exactly 8 bytes, although only
     * {@code bytes[index]} and {@code bytes[index + 1]} are read.
     *
     * @param bytes the array; must have exactly 8 elements
     * @param index position of the high byte
     * @return the decoded short
     * @throws Exception if {@code bytes.length != 8}
     */
    public static short toInt16(byte[] bytes, int index) throws Exception {
        if (bytes.length != 8) {
            throw new Exception("The length of the byte array must be at least 8 bytes long.");
        }
        return (short) (((0xff & bytes[index]) << 8) | (0xff & bytes[index + 1]));
    }

    /**
     * Reads an int from four bytes, big-endian.
     *
     * @param bytes the array; must have exactly 4 elements
     * @param index position of the most significant byte; only {@code 0} is valid
     * @return the decoded int
     * @throws Exception if {@code bytes.length != 4}
     */
    public static int toInt32(byte[] bytes, int index) throws Exception {
        if (bytes.length != 4) {
            throw new Exception("The length of the byte array must be at least 4 bytes long.");
        }
        return (0xff & bytes[index]) << 24 |
                (0xff & bytes[index + 1]) << 16 |
                (0xff & bytes[index + 2]) << 8 |
                (0xff & bytes[index + 3]);
    }

    /**
     * Reads a long from eight bytes, big-endian.
     *
     * @param bytes the array; must have exactly 8 elements
     * @param index position of the most significant byte; only {@code 0} is valid
     * @return the decoded long
     * @throws Exception if {@code bytes.length != 8}
     */
    public static long toInt64(byte[] bytes, int index) throws Exception {
        if (bytes.length != 8) {
            throw new Exception("The length of the byte array must be at least 8 bytes long.");
        }
        return (long)(0xff & bytes[index]) << 56  |
                (long)(0xff & bytes[index + 1]) << 48  |
                (long)(0xff & bytes[index + 2]) << 40  |
                (long)(0xff & bytes[index + 3]) << 32  |
                (long)(0xff & bytes[index + 4]) << 24  |
                (long)(0xff & bytes[index + 5]) << 16  |
                (long)(0xff & bytes[index + 6]) << 8   |
                (long) (0xff & bytes[index + 7]);
    }

    /**
     * Reads a float from its 4-byte IEEE 754 representation, big-endian.
     *
     * @param bytes the array; must have exactly 4 elements
     * @param index position of the first byte; only {@code 0} is valid
     * @return the decoded float
     * @throws Exception if {@code bytes.length != 4}
     */
    public static float toSingle(byte[] bytes, int index) throws Exception {
        if (bytes.length != 4) {
            throw new Exception("The length of the byte array must be at least 4 bytes long.");
        }
        return Float.intBitsToFloat(toInt32(bytes, index));
    }

    /**
     * Converts an int to four bytes in the platform's native byte order, like .NET's
     * {@code BitConverter.GetBytes(int)}.
     *
     * <p>Note: the result depends on the machine: little-endian (low byte first) on x86 and ARM,
     * which is what {@link TlkFile}'s character decoding relies on. {@link #getBytes(int)} is the
     * fixed big-endian variant.
     *
     * @param value the value
     * @return the 4 bytes of {@code value} in {@link ByteOrder#nativeOrder()}
     */
    public static byte[] GetBytes(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        buffer.putInt(value);
        return buffer.array();
    }

    /**
     * Decodes bytes as text with the platform default charset. (.NET's
     * {@code BitConverter.ToString} instead returns a hex dump such as {@code "01-AB"}.)
     *
     * @param bytes the encoded text
     * @return the decoded string
     * @throws Exception if {@code bytes} is {@code null}
     */
    public static String toString(byte[] bytes) throws Exception {
        if (bytes == null) {
            throw new Exception("The byte array must have at least 1 byte.");
        }
        return new String(bytes);
    }
}
