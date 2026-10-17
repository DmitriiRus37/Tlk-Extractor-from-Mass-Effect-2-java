package ru;

import java.util.List;

/**
 * Decodes the NUL-terminated strings of a TLK data block with its Huffman tree; used by
 * {@link TlkFile#loadTlkData(String)}.
 *
 * <p>The bit stream is read starting at the least significant bit of each byte (the order of
 * {@link BitArray#getRev(int)}). From the root, a 1 bit selects the right child and a 0 bit the
 * left one; a negative child id is a leaf for {@link TlkFile#leafCharacter(int)}, after which
 * decoding restarts at the root, and the {@code '\0'} leaf ends the string.
 *
 * <p>For trees of at most {@link #MAX_TABLE_NODES} nodes (a TLK tree has one node less than it
 * has distinct characters, about 190 in {@code BIOGame_RUS.tlk}) the decoder precomputes, for
 * every node and every byte value, the characters produced and the node reached after those 8
 * bits, and then decodes a byte per step. Bits before the first byte boundary, and every bit of
 * larger trees, are decoded one at a time. Both ways give the same result.
 */
final class HuffmanDecoder {

    /**
     * Largest tree decoded with tables; the tables take about {@code 21 * 256} bytes per node,
     * some 5 MB at this size.
     */
    static final int MAX_TABLE_NODES = 1024;
    /** Characters one byte can produce at most: 8 one-bit codes. */
    private static final int MAX_CHARS_PER_BYTE = Byte.SIZE;
    /** Flag in {@link #charCount}: the byte holds the end of the string. */
    private static final int TERMINATED = 0x80;

    /** The tree as {@code (left, right)} pairs: node {@code n} at {@code 2n} and {@code 2n + 1}. */
    private final int[] tree;
    /** The data block. */
    private final byte[] data;
    /**
     * Per table entry {@code node * 256 + byte}: the node reached after the 8 bits; {@code null}
     * if the tree is decoded bit by bit.
     */
    private final int[] nextNode;
    /**
     * Per table entry: the number of characters the byte completes, before the terminator if it
     * holds one, plus {@link #TERMINATED} if it does; {@code null} without tables.
     */
    private final byte[] charCount;
    /** Per table entry: its characters, {@link #MAX_CHARS_PER_BYTE} slots each; {@code null} without tables. */
    private final char[] chars;

    /**
     * Creates a decoder, building the tables if the tree is small enough.
     *
     * @param nodes the tree, root first; every non-negative child id must be a node index
     * @param data  the data block; not copied
     */
    HuffmanDecoder(List<TlkFile.HuffmanNode> nodes, byte[] data) {
        this(nodes, data, nodes.size() <= MAX_TABLE_NODES);
    }

    /**
     * Creates a decoder that uses tables or not; lets tests compare both ways.
     *
     * @param nodes     the tree, root first; every non-negative child id must be a node index
     * @param data      the data block; not copied
     * @param useTables whether to build and use the byte tables
     */
    HuffmanDecoder(List<TlkFile.HuffmanNode> nodes, byte[] data, boolean useTables) {
        this.data = data;
        tree = new int[2 * nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            tree[2 * i] = nodes.get(i).leftNodeId();
            tree[2 * i + 1] = nodes.get(i).rightNodeId();
        }
        if (useTables && !nodes.isEmpty()) {
            int entries = nodes.size() << 8;
            nextNode = new int[entries];
            charCount = new byte[entries];
            chars = new char[entries * MAX_CHARS_PER_BYTE];
            buildTables();
        } else {
            nextNode = null;
            charCount = null;
            chars = null;
        }
    }

    /** Fills the tables by walking the 8 bits of every byte value from every node. */
    private void buildTables() {
        for (int entry = 0; entry < nextNode.length; entry++) {
            int node = entry >>> 8;
            int value = entry & 0xff;
            int count = 0;
            int flags = 0;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                int next = tree[2 * node + (value >>> bit & 1)];
                if (next >= 0) {
                    node = next;
                    continue;
                }
                char c = TlkFile.leafCharacter(next);
                if (c == '\0') {
                    flags = TERMINATED;
                    break;
                }
                chars[entry * MAX_CHARS_PER_BYTE + count++] = c;
                node = 0;
            }
            nextNode[entry] = node;
            charCount[entry] = (byte) (count | flags);
        }
    }

    /**
     * Decodes the string starting at a bit offset.
     *
     * @param bitOffset bit index to start at, not negative
     * @param text      receives the characters; cleared first
     * @return {@code true} if the string was terminated, {@code false} if the data ended first
     *         ({@code text} then holds what was decoded)
     * @throws IllegalStateException if the tree is empty
     */
    boolean decode(int bitOffset, StringBuilder text) {
        if (tree.length == 0) {
            throw new IllegalStateException("No Huffman tree");
        }
        text.setLength(0);
        long bitCount = (long) data.length * Byte.SIZE;
        long bit = bitOffset;
        int node = 0;
        // bit by bit up to the first byte boundary, or to the end without tables
        while (bit < bitCount && (nextNode == null || (bit & 7) != 0)) {
            int next = tree[2 * node + (data[(int) (bit >>> 3)] >> (int) (bit & 7) & 1)];
            bit++;
            if (next >= 0) {
                node = next;
                continue;
            }
            char c = TlkFile.leafCharacter(next);
            if (c == '\0') {
                return true;
            }
            text.append(c);
            node = 0;
        }
        if (nextNode == null) {
            return false;
        }
        // then a byte per step
        for (int index = (int) (bit >>> 3); index < data.length; index++) {
            int entry = node << 8 | data[index] & 0xff;
            int count = charCount[entry] & 0xff;
            text.append(chars, entry * MAX_CHARS_PER_BYTE, count & ~TERMINATED);
            if ((count & TERMINATED) != 0) {
                return true;
            }
            node = nextNode[entry];
        }
        return false;
    }
}
