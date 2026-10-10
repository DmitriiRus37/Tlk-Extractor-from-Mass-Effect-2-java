package ru;/*
 * Copyright (c) 1997, 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * A fixed-length packed array of booleans, adapted from the JDK-internal
 * {@code sun.security.util.BitArray}.
 *
 * <p>Bits are stored eight per byte. The constructors, {@link #get(int)}, {@link #set(int, boolean)},
 * {@link #toByteArray()} and {@link #toString()} use <em>MSB-first</em> order: bit index 0 is the
 * most significant ({@code 0x80}) bit of byte 0. {@link #getRev(int)} reads the same storage
 * <em>LSB-first</em> (bit index 0 is the {@code 0x01} bit of byte 0), which is the layout of .NET's
 * {@code System.Collections.BitArray} and therefore of the compressed string data in TLK files.
 *
 * <p>In this project {@link TlkFile} wraps the raw TLK data block with {@link #BitArray(byte[])}
 * and decodes it with {@link #getRev(int)}, while {@code HuffmanCompression} stores each
 * character's Huffman code in a {@code BitArray} built with {@link #BitArray(boolean[])} and reads
 * it back with {@link #get(int)}, so code bit {@code i} is simply {@code get(i)}.
 *
 * @author Joshua Bloch
 * @author Douglas Hoover
 */

public class BitArray {

    /**
     * Backing storage, {@code ceil(length / 8)} bytes, MSB-first. Bits of the last byte beyond
     * {@link #length} are always zero.
     */
    private final byte[] repn;
    /** Number of bits in this array. */
    private final int length;

    /** Number of bits per storage unit (one {@code byte}). */
    public static final byte BITS_PER_UNIT = 8;

    /**
     * Returns the index of the byte in {@link #repn} that holds a bit.
     *
     * @param idx bit index
     * @return {@code idx / 8}
     */
    private static int subscript(int idx) {
        return idx / BITS_PER_UNIT;
    }

    /**
     * Returns the mask that selects a bit within its byte, MSB-first.
     *
     * @param idx bit index
     * @return {@code 0x80} for {@code idx % 8 == 0} down to {@code 0x01} for {@code idx % 8 == 7}
     */
    private static int positionBigEndian(int idx) { // bits big-endian in each unit
        return 1 << (BITS_PER_UNIT - 1 - (idx % BITS_PER_UNIT));
    }

    /**
     * Creates a {@code BitArray} of the specified size, initialized to zeros.
     *
     * @param length number of bits
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public BitArray(int length) throws IllegalArgumentException {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length for BitArray");
        }
        this.length = length;
        repn = new byte[(length + BITS_PER_UNIT - 1)/BITS_PER_UNIT];
    }


    /**
     * Creates a {@code BitArray} of {@code 8 * a.length} bits holding a copy of {@code a}. The
     * most significant bit of {@code a[0]} gets index zero for {@link #get(int)}; for
     * {@link #getRev(int)} index zero is the least significant bit of {@code a[0]}.
     *
     * <p>The masking of unused bits in the last byte is a leftover of the original two-argument
     * {@code BitArray(int, byte[])} constructor and has no effect here, since the length is always
     * a multiple of 8.
     *
     * @param a the bytes to copy
     * @throws IllegalArgumentException never thrown by this constructor; declared for
     *                                  compatibility with the original
     */
    public BitArray(byte[] a) throws IllegalArgumentException {
        length = a.length * BITS_PER_UNIT;
        int repLength = (length + BITS_PER_UNIT - 1) / BITS_PER_UNIT;
        int unusedBits = repLength * BITS_PER_UNIT - length;
        byte bitMask = (byte) (0xFF << unusedBits);

        /*
         normalize the representation:
          1. discard extra bytes
          2. zero out extra bits in the last byte
         */
        repn = new byte[repLength];
        System.arraycopy(a, 0, repn, 0, repLength);
        if (repLength > 0) {
            repn[repLength - 1] &= bitMask;
        }
    }

    /**
     * Create a BitArray whose bits are those of the given array
     * of Booleans: {@code get(i) == bits[i]}.
     *
     * @param bits the bit values; its length becomes the length of this array
     */
    public BitArray(boolean[] bits) {
        length = bits.length;
        repn = new byte[(length + 7)/8];

        for (int i=0; i < length; i++) {
            set(i, bits[i]);
        }
    }


    /**
     *  Copy constructor (for cloning).
     *
     * @param ba the array to copy; its backing bytes are cloned
     */
    private BitArray(BitArray ba) {
        length = ba.length;
        repn = ba.repn.clone();
    }

    /**
     *  Returns the indexed bit in this BitArray, counting MSB-first within each byte.
     *
     * @param index bit index, {@code 0 <= index < length()}
     * @return the bit value
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public boolean get(int index) throws ArrayIndexOutOfBoundsException {
        if (index < 0 || index >= length) {
            throw new ArrayIndexOutOfBoundsException(String.valueOf(index));
        }
        return (repn[subscript(index)] & positionBigEndian(index)) != 0;
    }

    /**
     * Returns the indexed bit counting LSB-first within each byte ("reversed" relative to
     * {@link #get(int)}): index 0 is the {@code 0x01} bit of byte 0, index 7 its {@code 0x80}
     * bit, index 8 the {@code 0x01} bit of byte 1, and so on. This matches .NET's
     * {@code BitArray} indexing, which is how TLK files lay out their compressed data.
     *
     * <p>Unpacks the whole containing byte into a temporary {@code boolean[8]} on every call.
     *
     * @param index bit index, {@code 0 <= index < length()}
     * @return the bit value
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public boolean getRev(int index) throws ArrayIndexOutOfBoundsException {
        if (index < 0 || index >= length) {
            throw new ArrayIndexOutOfBoundsException(String.valueOf(index));
        }
        boolean[] bitArray = new boolean[Byte.SIZE];
        for (int i = 0; i<Byte.SIZE; i++) {
            bitArray[Byte.SIZE-1-i] = (repn[subscript(index)] >> i & 0x1) != 0x0;
        }
        return getBit(bitArray, index, Endian.LittleEndian);
    }

    /**
     * Picks one bit out of an unpacked byte.
     *
     * @param byteImpl the 8 bits of a byte, most significant first ({@code byteImpl[0]} is the
     *                 {@code 0x80} bit)
     * @param index    bit index; only {@code index % 8} is used
     * @param e        {@link Endian#BigEndian} to count from the most significant bit,
     *                 {@link Endian#LittleEndian} to count from the least significant bit
     * @return the selected bit
     */
    private boolean getBit(boolean[] byteImpl, int index, Endian e) {
        return byteImpl[e.equals(Endian.BigEndian) ?
                index % BITS_PER_UNIT :
                Byte.SIZE - 1 - index % BITS_PER_UNIT];
    }

    /**
     *  Sets the indexed bit in this BitArray, counting MSB-first within each byte (the same
     *  order as {@link #get(int)}).
     *
     * @param index bit index, {@code 0 <= index < length()}
     * @param value the new bit value
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public void set(int index, boolean value)
            throws ArrayIndexOutOfBoundsException {
        if (index < 0 || index >= length) {
            throw new ArrayIndexOutOfBoundsException(String.valueOf(index));
        }
        int idx = subscript(index);
        int bit = positionBigEndian(index);
        repn[idx] = value ? (byte) (repn[idx] | bit) : (byte) (repn[idx] & ~bit);
    }

    /**
     * Returns the length of this BitArray.
     *
     * @return the number of bits
     */
    public int length() {
        return length;
    }

    /**
     * Returns a Byte array containing the contents of this BitArray.
     * The bit stored at index zero in this BitArray will be copied
     * into the most significant bit of the zeroth element of the
     * returned byte array.  The last byte of the returned byte array
     * will contain zeros in any bits that do not have corresponding
     * bits in the BitArray.  (This matters only if the BitArray's size
     * is not a multiple of 8.)
     *
     * @return a copy of the backing bytes, {@code ceil(length() / 8)} long
     */
    public byte[] toByteArray() {
        return repn.clone();
    }

    /**
     * Two bit arrays are equal if they have the same length and the same bits.
     *
     * @param obj the object to compare with
     * @return {@code true} if {@code obj} is a {@code BitArray} with the same length and contents
     */
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof BitArray)) {
            return false;
        }
        BitArray ba = (BitArray) obj;
        return (ba.length == length) && IntStream.range(0, repn.length).noneMatch(i -> repn[i] != ba.repn[i]);
    }

    /**
     * Return a boolean array with the same bit values as this BitArray.
     *
     * @return a new array {@code b} of length {@link #length()} with {@code b[i] == get(i)}
     */
    public boolean[] toBooleanArray() {
        boolean[] bits = new boolean[length];

        for (int i=0; i < length; i++) {
            bits[i] = get(i);
        }
        return bits;
    }

    /**
     * Returns a hash code value for this bit array.
     *
     * @return  a hash code value for this bit array.
     */
    public int hashCode() {
        int hashCode = 0;

        for (byte b : repn) {
            hashCode = 31 * hashCode + b;
        }
        return hashCode ^ length;
    }


    /**
     * Returns an independent copy of this array. The class does not implement
     * {@link Cloneable}; the copy is made with the private copy constructor, not
     * {@code super.clone()}.
     *
     * @return a new {@code BitArray} with the same length and bits
     */
    @Override
    public Object clone() {
        return new BitArray(this);
    }

    /**
     * ASCII {@code '0'}/{@code '1'} spellings of every 4-bit value, indexed by that value; used
     * by {@link #toString()}.
     */
    private static final byte[][] NYBBLE = {
            { (byte)'0',(byte)'0',(byte)'0',(byte)'0'},
            { (byte)'0',(byte)'0',(byte)'0',(byte)'1'},
            { (byte)'0',(byte)'0',(byte)'1',(byte)'0'},
            { (byte)'0',(byte)'0',(byte)'1',(byte)'1'},
            { (byte)'0',(byte)'1',(byte)'0',(byte)'0'},
            { (byte)'0',(byte)'1',(byte)'0',(byte)'1'},
            { (byte)'0',(byte)'1',(byte)'1',(byte)'0'},
            { (byte)'0',(byte)'1',(byte)'1',(byte)'1'},
            { (byte)'1',(byte)'0',(byte)'0',(byte)'0'},
            { (byte)'1',(byte)'0',(byte)'0',(byte)'1'},
            { (byte)'1',(byte)'0',(byte)'1',(byte)'0'},
            { (byte)'1',(byte)'0',(byte)'1',(byte)'1'},
            { (byte)'1',(byte)'1',(byte)'0',(byte)'0'},
            { (byte)'1',(byte)'1',(byte)'0',(byte)'1'},
            { (byte)'1',(byte)'1',(byte)'1',(byte)'0'},
            { (byte)'1',(byte)'1',(byte)'1',(byte)'1'}
    };

    /** Number of bytes {@link #toString()} prints per line. */
    private static final int BYTES_PER_LINE = 8;

    /**
     *  Returns a String representation of this BitArray: the bits as {@code '0'}/{@code '1'}
     *  characters, MSB-first, in groups of 8 separated by a space, with a line break after every
     *  8 groups. Only the valid bits of the last byte are printed.
     *
     *  <p>Note: throws {@link ArrayIndexOutOfBoundsException} for an empty (zero-length) array;
     *  the JDK original returns {@code ""} in that case.
     *
     * @return the textual bit dump
     */
    public String toString() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int i = 0; i < repn.length - 1; i++) {
            out.write(NYBBLE[(repn[i] >> 4) & 0x0F], 0, 4);
            out.write(NYBBLE[repn[i] & 0x0F], 0, 4);
            out.write(i % BYTES_PER_LINE == BYTES_PER_LINE - 1 ? '\n' : ' ');
        }

        // in last byte of repn, use only the valid bits
        for (int i = BITS_PER_UNIT * (repn.length - 1); i < length; i++) {
            out.write(get(i) ? '1' : '0');
        }
        return out.toString();
    }

    /**
     * Returns a copy shortened after the last set bit, rounded up to whole bytes.
     *
     * <p>Note: unlike the JDK original, which keeps exactly {@code lastSetBit + 1} bits, the copy
     * is built with {@link #BitArray(byte[])}, so its length is a multiple of 8 and zero bits
     * after the last set bit within the same byte are kept. Not used in this project.
     *
     * @return the bytes up to and including the one holding the last {@code 1} bit, or a
     *         one-bit array containing {@code 0} if no bit is set
     */
    public BitArray truncate() {
        for (int i=length-1; i>=0; i--) {
            if (get(i)) {
                return new BitArray(Arrays.copyOf(repn, (i + BITS_PER_UNIT)/BITS_PER_UNIT));
            }
        }
        return new BitArray(1);
    }

}