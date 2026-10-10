package ru;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds small, valid (or deliberately broken) PC TLK files in memory, independently of the
 * production code, so that {@link TlkFile} can be tested against known input.
 *
 * <p>Layout (see {@link TlkFile}): 28-byte header of seven little-endian ints, the
 * {@code (stringId, bitOffset)} string refs of the male and then the female section, the
 * Huffman tree as {@code (left, right)} int pairs (node 0 is the root, a negative child
 * {@code v} is a leaf for the character {@code -1 - v}, bit 0 goes left, bit 1 goes right), and
 * the NUL-terminated Huffman-coded strings packed least significant bit first.
 *
 * <p>By default a balanced tree over all characters used (plus {@code '\0'}) is generated; a
 * tree and code table from elsewhere (e.g. {@link HuffmanCompression}) can be supplied with
 * {@link #tree(List, Map)} instead. Each ref that has its own text is encoded separately, in ref
 * order; refs can also point at another ref's text or into its middle (shared tail), or carry a
 * raw bit offset such as {@code -1}.
 */
final class TlkTestFile {

    static final int MAGIC = 7040084;
    static final int XBOX_MAGIC = 1416391424;
    static final int HEADER_SIZE = 28;

    /** One string ref of the file being built. */
    private static final class Ref {
        final int id;
        final String text;
        /** Explicit bit offset (e.g. -1), or null. */
        final Integer rawOffset;
        /** Index of the ref whose text this one points into, or -1. */
        final int target;
        /** Number of characters of the target's text to skip (tail sharing). */
        final int skipChars;

        Ref(int id, String text, Integer rawOffset, int target, int skipChars) {
            this.id = id;
            this.text = text;
            this.rawOffset = rawOffset;
            this.target = target;
            this.skipChars = skipChars;
        }
    }

    private final List<Ref> male = new ArrayList<>();
    private final List<Ref> female = new ArrayList<>();

    private int magic = MAGIC;
    private int version = 3;
    private int minVersion = 2;
    private Integer entry1CountOverride;
    private Integer entry2CountOverride;
    private Integer treeNodeCountOverride;
    private Integer dataLenOverride;

    private List<Integer> externalTree;
    private Map<Character, String> externalCodes;

    // layout of the last build
    private int treeOffset;
    private int dataOffset;

    /** Adds a male-section ref with its own text. */
    TlkTestFile male(int id, String text) {
        male.add(new Ref(id, text, null, -1, 0));
        return this;
    }

    /** Adds a female-section ref with its own text. */
    TlkTestFile female(int id, String text) {
        female.add(new Ref(id, text, null, -1, 0));
        return this;
    }

    /** Adds a male-section ref with an explicit bit offset (normally -1: no text). */
    TlkTestFile maleRaw(int id, int bitOffset) {
        male.add(new Ref(id, null, bitOffset, -1, 0));
        return this;
    }

    /** Adds a female-section ref with an explicit bit offset (normally -1: no text). */
    TlkTestFile femaleRaw(int id, int bitOffset) {
        female.add(new Ref(id, null, bitOffset, -1, 0));
        return this;
    }

    /**
     * Adds a male-section ref sharing the text of an earlier ref, starting {@code skipChars}
     * characters into it (0: the same offset).
     *
     * @param refIndex index over all refs added so far (male first, then female)
     */
    TlkTestFile maleSharing(int id, int refIndex, int skipChars) {
        male.add(new Ref(id, null, null, refIndex, skipChars));
        return this;
    }

    /** Female-section variant of {@link #maleSharing(int, int, int)}. */
    TlkTestFile femaleSharing(int id, int refIndex, int skipChars) {
        female.add(new Ref(id, null, null, refIndex, skipChars));
        return this;
    }

    TlkTestFile magic(int value) {
        this.magic = value;
        return this;
    }

    TlkTestFile versions(int ver, int minVer) {
        this.version = ver;
        this.minVersion = minVer;
        return this;
    }

    TlkTestFile entry1Count(int value) {
        this.entry1CountOverride = value;
        return this;
    }

    TlkTestFile entry2Count(int value) {
        this.entry2CountOverride = value;
        return this;
    }

    TlkTestFile treeNodeCount(int value) {
        this.treeNodeCountOverride = value;
        return this;
    }

    TlkTestFile dataLen(int value) {
        this.dataLenOverride = value;
        return this;
    }

    /**
     * Uses the given tree and codes instead of generating a balanced tree.
     *
     * @param treeBuffer {@code (left, right)} child ids of node 0, node 1, ...
     * @param codes      code of each character, {@code get(i)} being the i-th branch
     */
    TlkTestFile tree(List<Integer> treeBuffer, Map<Character, BitArray> codes) {
        externalTree = new ArrayList<>(treeBuffer);
        externalCodes = new HashMap<>();
        for (Map.Entry<Character, BitArray> e : codes.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < e.getValue().length(); i++) {
                sb.append(e.getValue().get(i) ? '1' : '0');
            }
            externalCodes.put(e.getKey(), sb.toString());
        }
        return this;
    }

    /** Byte offset of the Huffman tree in the last built file. */
    int treeOffset() {
        return treeOffset;
    }

    /** Byte offset of the data block in the last built file. */
    int dataOffset() {
        return dataOffset;
    }

    Path writeTo(Path file) throws IOException {
        Files.write(file, build());
        return file;
    }

    byte[] build() {
        List<Ref> all = new ArrayList<>(male);
        all.addAll(female);

        List<Integer> tree;
        Map<Character, String> codes;
        if (externalTree != null) {
            tree = externalTree;
            codes = externalCodes;
        } else {
            tree = new ArrayList<>();
            codes = new HashMap<>();
            buildBalancedTree(all, tree, codes);
        }

        // encode every ref that has its own text, in ref order
        StringBuilder bits = new StringBuilder();
        int[] offsets = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Ref r = all.get(i);
            if (r.text != null) {
                offsets[i] = bits.length();
                String s = r.text + '\0';
                for (int k = 0; k < s.length(); k++) {
                    String code = codes.get(s.charAt(k));
                    if (code == null) {
                        throw new IllegalArgumentException("no code for U+" + Integer.toHexString(s.charAt(k)));
                    }
                    bits.append(code);
                }
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Ref r = all.get(i);
            if (r.rawOffset != null) {
                offsets[i] = r.rawOffset;
            } else if (r.text == null) {
                int offset = offsets[r.target];
                String targetText = all.get(r.target).text;
                for (int k = 0; k < r.skipChars; k++) {
                    offset += codes.get(targetText.charAt(k)).length();
                }
                offsets[i] = offset;
            }
        }

        byte[] data = new byte[(bits.length() + 7) / 8];
        for (int k = 0; k < bits.length(); k++) {
            if (bits.charAt(k) == '1') {
                data[k / 8] |= (byte) (1 << (k % 8));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, magic);
        writeInt(out, version);
        writeInt(out, minVersion);
        writeInt(out, entry1CountOverride != null ? entry1CountOverride : male.size());
        writeInt(out, entry2CountOverride != null ? entry2CountOverride : female.size());
        writeInt(out, treeNodeCountOverride != null ? treeNodeCountOverride : tree.size() / 2);
        writeInt(out, dataLenOverride != null ? dataLenOverride : data.length);
        for (int i = 0; i < all.size(); i++) {
            writeInt(out, all.get(i).id);
            writeInt(out, offsets[i]);
        }
        treeOffset = out.size();
        for (int child : tree) {
            writeInt(out, child);
        }
        dataOffset = out.size();
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /**
     * Generates a balanced tree over the sorted set of characters used plus {@code '\0'}; with a
     * single character the root gets that leaf on both sides.
     */
    private static void buildBalancedTree(List<Ref> refs, List<Integer> tree, Map<Character, String> codes) {
        TreeSet<Character> set = new TreeSet<>();
        set.add('\0');
        for (Ref r : refs) {
            if (r.text != null) {
                for (char c : r.text.toCharArray()) {
                    set.add(c);
                }
            }
        }
        List<Character> chars = new ArrayList<>(set);
        if (chars.size() == 1) {
            int leaf = -1 - chars.get(0);
            tree.add(leaf);
            tree.add(leaf);
            codes.put(chars.get(0), "0");
            return;
        }
        addNode(Collections.unmodifiableList(chars), tree, codes, "");
    }

    /** Returns the child id for {@code chars}: a leaf id, or the index of a new inner node. */
    private static int addNode(List<Character> chars, List<Integer> tree, Map<Character, String> codes, String prefix) {
        if (chars.size() == 1) {
            codes.put(chars.get(0), prefix);
            return -1 - chars.get(0);
        }
        int index = tree.size() / 2;
        tree.add(0);
        tree.add(0);
        int mid = chars.size() / 2;
        int left = addNode(chars.subList(0, mid), tree, codes, prefix + "0");
        int right = addNode(chars.subList(mid, chars.size()), tree, codes, prefix + "1");
        tree.set(2 * index, left);
        tree.set(2 * index + 1, right);
        return index;
    }

    static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    /** Little-endian bytes of the given ints. */
    static byte[] intsLE(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            writeInt(out, v);
        }
        return out.toByteArray();
    }
}
