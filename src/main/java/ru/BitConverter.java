package ru;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Partial Java port of .NET's {@code System.BitConverter}: converts primitives to and from byte
 * arrays.
 *
 * <p>Unlike .NET, which uses the machine's byte order (little-endian on PCs), the plain methods
 * here are <em>big-endian</em>: the {@code getBytes(char/short/int/long/float/double)} overloads
 * emit the most significant byte first, and {@code toChar}, {@code toInt16}, {@code toInt32},
 * {@code toInt64}, {@code toSingle} and {@code toDouble} read the first byte as the most
 * significant one, so each {@code to*} method is the inverse of the matching {@code getBytes}.
 * {@link #getBytesLittleEndian(int)} and {@link #toCharLittleEndian(byte[], int)} are the
 * little-endian variants (what .NET produces on a PC), independent of the platform's byte order.
 *
 * <p>Like their .NET counterparts, the {@code to*} methods read at {@code index} and require
 * {@code bytes.length >= index + size}; extra bytes are ignored.
 *
 * <p>Not used by the conversion code: {@link TlkFile} turns a Huffman leaf id back into a
 * character with plain arithmetic, which is what the C# original's
 * {@code BitConverter.ToChar(BitConverter.GetBytes(0xffff - id), 0)} amounts to.
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
        return new byte[]{(byte) (x ? 1 : 0)};
    }

    /**
     * Converts a char to two bytes, big-endian; the inverse of {@link #toChar(byte[], int)}.
     *
     * @param c the character
     * @return {@code {high byte, low byte}}
     */
    public static byte[] getBytes(char c) {
        return ByteBuffer.allocate(Character.BYTES).putChar(c).array();
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
        return ByteBuffer.allocate(Short.BYTES).putShort(x).array();
    }

    /**
     * Converts an int to four bytes, big-endian. See {@link #getBytesLittleEndian(int)} for the
     * little-endian variant.
     *
     * @param x the value
     * @return the bytes of {@code x}, most significant first
     */
    public static byte[] getBytes(int x) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(x).array();
    }

    /**
     * Converts a long to eight bytes, big-endian.
     *
     * @param x the value
     * @return the bytes of {@code x}, most significant first
     */
    public static byte[] getBytes(long x) {
        return ByteBuffer.allocate(Long.BYTES).putLong(x).array();
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
     * Encodes a string as UTF-8, whatever the platform default charset.
     *
     * @param x the string
     * @return the encoded bytes
     */
    public static byte[] getBytes(String x) {
        return x.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Converts an int to four bytes, little-endian (low byte first) on every platform: what .NET's
     * {@code BitConverter.GetBytes(int)} returns on a PC.
     *
     * @param value the value
     * @return the bytes of {@code value}, least significant first
     */
    public static byte[] getBytesLittleEndian(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
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
     * Reinterprets the bits of a long as a double, like .NET's
     * {@code BitConverter.Int64BitsToDouble}; the inverse of {@link #doubleToInt64Bits(double)}.
     *
     * @param x the IEEE 754 bit pattern
     * @return the double with these bits
     */
    public static double int64BitsToDouble(long x) {
        return Double.longBitsToDouble(x);
    }

    /**
     * Wraps the {@code size} bytes at {@code index} in a big-endian buffer after checking that
     * they exist.
     *
     * @param bytes  the array
     * @param index  position of the first byte
     * @param size   number of bytes needed
     * @param method name of the calling method, for the error message
     * @return a buffer positioned at {@code index}
     * @throws NullPointerException      if {@code bytes} is {@code null}
     * @throws IndexOutOfBoundsException if {@code index} is negative or fewer than {@code size}
     *                                   bytes follow it
     */
    private static ByteBuffer wrap(byte[] bytes, int index, int size, String method) {
        if (bytes == null) {
            throw new NullPointerException(method + ": byte array is null");
        }
        if (index < 0 || index > bytes.length - size) {
            throw new IndexOutOfBoundsException(method + " needs " + size + (size == 1 ? " byte" : " bytes")
                    + " at index " + index + ", but the array has " + bytes.length);
        }
        return ByteBuffer.wrap(bytes, index, size);
    }

    /**
     * Reads a boolean from one byte.
     *
     * @param bytes the array
     * @param index position of the byte
     * @return {@code true} if the byte is non-zero
     * @throws IndexOutOfBoundsException if there is no byte at {@code index}
     */
    public static boolean toBoolean(byte[] bytes, int index) {
        return wrap(bytes, index, 1, "toBoolean").get() != 0;
    }

    /**
     * Reads a char from two bytes, big-endian; the inverse of {@link #getBytes(char)}.
     *
     * @param bytes the array
     * @param index position of the high byte
     * @return {@code (bytes[index] << 8) | bytes[index + 1]} as an unsigned 16-bit char
     * @throws IndexOutOfBoundsException if fewer than 2 bytes start at {@code index}
     */
    public static char toChar(byte[] bytes, int index) {
        return wrap(bytes, index, Character.BYTES, "toChar").getChar();
    }

    /**
     * Reads a char from two bytes, little-endian, i.e. what .NET's {@code BitConverter.ToChar}
     * does on a PC. The array is not modified.
     *
     * @param bytes the array
     * @param index position of the low byte
     * @return {@code (bytes[index + 1] << 8) | bytes[index]} as an unsigned 16-bit char
     * @throws IndexOutOfBoundsException if fewer than 2 bytes start at {@code index}
     */
    public static char toCharLittleEndian(byte[] bytes, int index) {
        return wrap(bytes, index, Character.BYTES, "toCharLittleEndian").order(ByteOrder.LITTLE_ENDIAN).getChar();
    }

    /**
     * Reads a double from its 8-byte IEEE 754 representation, big-endian.
     *
     * @param bytes the array
     * @param index position of the first byte
     * @return the decoded double
     * @throws IndexOutOfBoundsException if fewer than 8 bytes start at {@code index}
     */
    public static double toDouble(byte[] bytes, int index) {
        return wrap(bytes, index, Double.BYTES, "toDouble").getDouble();
    }

    /**
     * Reads a short from two bytes, big-endian.
     *
     * @param bytes the array
     * @param index position of the high byte
     * @return the decoded short
     * @throws IndexOutOfBoundsException if fewer than 2 bytes start at {@code index}
     */
    public static short toInt16(byte[] bytes, int index) {
        return wrap(bytes, index, Short.BYTES, "toInt16").getShort();
    }

    /**
     * Reads an int from four bytes, big-endian.
     *
     * @param bytes the array
     * @param index position of the most significant byte
     * @return the decoded int
     * @throws IndexOutOfBoundsException if fewer than 4 bytes start at {@code index}
     */
    public static int toInt32(byte[] bytes, int index) {
        return wrap(bytes, index, Integer.BYTES, "toInt32").getInt();
    }

    /**
     * Reads a long from eight bytes, big-endian.
     *
     * @param bytes the array
     * @param index position of the most significant byte
     * @return the decoded long
     * @throws IndexOutOfBoundsException if fewer than 8 bytes start at {@code index}
     */
    public static long toInt64(byte[] bytes, int index) {
        return wrap(bytes, index, Long.BYTES, "toInt64").getLong();
    }

    /**
     * Reads a float from its 4-byte IEEE 754 representation, big-endian.
     *
     * @param bytes the array
     * @param index position of the first byte
     * @return the decoded float
     * @throws IndexOutOfBoundsException if fewer than 4 bytes start at {@code index}
     */
    public static float toSingle(byte[] bytes, int index) {
        return wrap(bytes, index, Float.BYTES, "toSingle").getFloat();
    }

    /**
     * Decodes UTF-8 bytes as text, the inverse of {@link #getBytes(String)}. (.NET's
     * {@code BitConverter.ToString} instead returns a hex dump such as {@code "01-AB"}.)
     *
     * @param bytes the encoded text
     * @return the decoded string
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public static String toString(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("toString: byte array is null");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
