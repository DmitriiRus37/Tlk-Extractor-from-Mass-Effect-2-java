package ru;

/**
 * Bit order within a byte.
 *
 * <p>Only used inside {@link BitArray} to decide which bit of a byte a bit index refers to:
 * {@link #BigEndian} counts from the most significant bit, {@link #LittleEndian} from the least
 * significant bit (the order of the compressed data in TLK files, see
 * {@link BitArray#getRev(int)}). It has nothing to do with the byte order of TLK integers, which
 * {@link TlkFile#readInt32(java.io.InputStream)} always reads as little-endian.
 */
public enum Endian {
    /** Most significant first: bit index 0 is the {@code 0x80} bit of a byte. */
    BigEndian,
    /** Least significant first: bit index 0 is the {@code 0x01} bit of a byte. */
    LittleEndian
}
