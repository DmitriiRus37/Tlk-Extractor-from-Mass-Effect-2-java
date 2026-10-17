package ru;

import java.util.Arrays;

/**
 * Numbers distinct {@code int} keys 0, 1, 2, ... in order of first occurrence: a hash table with
 * open addressing over plain arrays, so that unlike a {@code HashMap<Integer, Integer>} it boxes
 * nothing and allocates nothing per key. Used for the string ids and the bit offsets of the
 * 100k-entry tables of a TLK file.
 */
final class IntIndex {

    /** Multiplier for Fibonacci hashing: 2^32 divided by the golden ratio. */
    private static final int GOLDEN = 0x9E3779B9;

    /** The key of each index. */
    private int[] keys;
    /** Per slot, the index of its key plus 1; 0 for an empty slot. Its length is a power of two. */
    private int[] slots;
    /** {@code 32 - log2(slots.length)}: a hash shifted right by it is a slot number. */
    private int shift;
    /** Number of keys. */
    private int size;

    /**
     * Creates an empty index.
     *
     * @param expected number of keys expected; more are accepted, the table then grows
     */
    IntIndex(int expected) {
        keys = new int[Math.max(16, expected)];
        int capacity = 16;
        while (capacity < 2L * expected && capacity < 1 << 30) {
            capacity <<= 1;
        }
        slots = new int[capacity];
        shift = Integer.numberOfLeadingZeros(capacity) + 1;
    }

    /**
     * Returns the index of a key, adding the key if it is new.
     *
     * @param key the key
     * @return its index: the number of distinct keys added before it
     */
    int add(int key) {
        int mask = slots.length - 1;
        int slot = key * GOLDEN >>> shift;
        int found;
        while ((found = slots[slot]) != 0) {
            if (keys[found - 1] == key) {
                return found - 1;
            }
            slot = (slot + 1) & mask;
        }
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, 2 * size);
        }
        keys[size] = key;
        slots[slot] = ++size;
        if (2 * size > slots.length) {
            grow();
        }
        return size - 1;
    }

    /**
     * Returns the number of distinct keys.
     *
     * @return the number of keys added
     */
    int size() {
        return size;
    }

    /**
     * Returns the key with an index.
     *
     * @param index an index from {@link #add(int)}
     * @return its key
     */
    int key(int index) {
        return keys[index];
    }

    /** Doubles the table and puts the keys in again. */
    private void grow() {
        slots = new int[2 * slots.length];
        shift--;
        int mask = slots.length - 1;
        for (int i = 0; i < size; i++) {
            int slot = keys[i] * GOLDEN >>> shift;
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = i + 1;
        }
    }
}
