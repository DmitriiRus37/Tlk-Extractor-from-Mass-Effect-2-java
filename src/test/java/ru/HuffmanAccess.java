package ru;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflective access to the internals of {@link HuffmanCompression}, plus checks of its Huffman
 * code and an independent test-side TLK writer used as an oracle for
 * {@link HuffmanCompression#saveToTlkFile(String, java.util.function.DoubleConsumer)}.
 */
final class HuffmanAccess {

    /** View of one private {@code HuffmanCompression.TlkEntry}. */
    static final class Entry {
        final int id;
        final int position;
        /** Text without terminator, or null for an entry without text. */
        final String data;

        Entry(int id, int position, String data) {
            this.id = id;
            this.position = position;
            this.data = data;
        }
    }

    private HuffmanAccess() {
    }

    static List<Entry> entries(HuffmanCompression hc) {
        List<Object> raw = TestSupport.field(hc, "inputData");
        List<Entry> result = new ArrayList<>();
        for (Object o : raw) {
            Integer id = TestSupport.field(o, "stringId");
            Integer position = TestSupport.field(o, "position");
            String data = TestSupport.field(o, "data");
            result.add(new Entry(id, position, data));
        }
        return result;
    }

    static Map<Character, Integer> frequencies(HuffmanCompression hc) {
        return TestSupport.field(hc, "frequencyCount");
    }

    static Map<Character, BitArray> codes(HuffmanCompression hc) {
        return TestSupport.field(hc, "huffmanCodes");
    }

    static String version(HuffmanCompression hc) {
        return TestSupport.field(hc, "inputFileVersion");
    }

    static List<Integer> treeBuffer(HuffmanCompression hc) throws Exception {
        int[] buffer = TestSupport.call(hc, "treeToBuffer", TestSupport.types());
        List<Integer> result = new ArrayList<>();
        for (int v : buffer) {
            result.add(v);
        }
        return result;
    }

    /** Packs the bit sequences with {@link HuffmanCompression.BitWriter}. */
    static byte[] pack(List<BitArray> bits) {
        HuffmanCompression.BitWriter writer = new HuffmanCompression.BitWriter();
        int count = 0;
        for (BitArray b : bits) {
            assertEquals(count, writer.bitCount());
            writer.append(b);
            count += b.length();
        }
        assertEquals(count, writer.bitCount());
        return writer.toByteArray();
    }

    /** Evaluates {@code v1 sign v2} with {@link HuffmanCompression#compareVersions(String, String)}. */
    static boolean compareVersions(String v1, String v2, String sign) {
        int c = HuffmanCompression.compareVersions(v1, v2);
        switch (sign) {
            case "<":
                return c < 0;
            case ">":
                return c > 0;
            case "=":
                return c == 0;
            default:
                throw new IllegalArgumentException(sign);
        }
    }

    static String codeString(BitArray code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            sb.append(code.get(i) ? '1' : '0');
        }
        return sb.toString();
    }

    /** Asserts that no code is a prefix of another (and that codes are distinct). */
    static void assertPrefixFree(Map<Character, BitArray> codes) {
        List<String> all = new ArrayList<>();
        for (BitArray code : codes.values()) {
            all.add(codeString(code));
        }
        java.util.Collections.sort(all);
        // after sorting, a code that is a prefix of another sorts directly before one of them
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).startsWith(all.get(i - 1)),
                    "code " + all.get(i - 1) + " is a prefix of " + all.get(i));
        }
    }

    /** Kraft sum: exactly 1 for the codes of a full binary tree. */
    static double kraftSum(Map<Character, BitArray> codes) {
        double sum = 0;
        for (BitArray code : codes.values()) {
            sum += Math.pow(2, -code.length());
        }
        return sum;
    }

    /** Total number of bits needed to encode the counted characters with the given codes. */
    static long encodedBits(Map<Character, Integer> frequencies, Map<Character, BitArray> codes) {
        long total = 0;
        for (Map.Entry<Character, Integer> e : frequencies.entrySet()) {
            total += (long) e.getValue() * codes.get(e.getKey()).length();
        }
        return total;
    }

    /** Encoded size of an optimal prefix code (any Huffman code) for these frequencies. */
    static long optimalBits(Map<Character, Integer> frequencies) {
        PriorityQueue<Long> queue = new PriorityQueue<>();
        for (int f : frequencies.values()) {
            queue.add((long) f);
        }
        long total = 0;
        while (queue.size() > 1) {
            long merged = queue.poll() + queue.poll();
            total += merged;
            queue.add(merged);
        }
        return total;
    }

    /**
     * Follows every code through the serialised tree and checks that it ends at the leaf of its
     * own character, as {@link TlkFile} would decode it.
     */
    static void assertTreeDecodesCodes(List<Integer> tree, Map<Character, BitArray> codes) {
        assertEquals(0, tree.size() % 2);
        for (Map.Entry<Character, BitArray> e : codes.entrySet()) {
            BitArray code = e.getValue();
            int node = 0;
            for (int i = 0; i < code.length(); i++) {
                assertTrue(node >= 0, "code of U+" + Integer.toHexString(e.getKey()) + " passes a leaf");
                node = tree.get(2 * node + (code.get(i) ? 1 : 0));
            }
            assertEquals(-1 - e.getKey(), node, "leaf of U+" + Integer.toHexString(e.getKey()));
        }
    }

    /**
     * A correct TLK file for the loaded entries, built independently with the compressor's own
     * tree and codes (every text encoded separately, without sharing). The first entry of an id
     * goes to the male section, later ones to the female section, each section in position order.
     */
    static TlkTestFile oracleTlk(HuffmanCompression hc) throws Exception {
        TlkTestFile file = new TlkTestFile().tree(treeBuffer(hc), codes(hc));
        Set<Integer> seen = new HashSet<>();
        List<Entry> female = new ArrayList<>();
        for (Entry e : entries(hc)) {
            if (seen.add(e.id)) {
                add(file, e, true);
            } else {
                female.add(e);
            }
        }
        for (Entry e : female) {
            add(file, e, false);
        }
        return file;
    }

    private static void add(TlkTestFile file, Entry e, boolean male) {
        if (e.data == null) {
            if (male) {
                file.maleRaw(e.id, -1);
            } else {
                file.femaleRaw(e.id, -1);
            }
        } else if (male) {
            file.male(e.id, e.data);
        } else {
            file.female(e.id, e.data);
        }
    }

    /**
     * The data block {@code saveToTlkFile} should write: the codes of every distinct text plus
     * its terminator, in order of first occurrence (position order), packed LSB first (computed
     * independently of the production packing code).
     */
    static byte[] expectedDataBlock(HuffmanCompression hc) {
        Map<Character, BitArray> codes = codes(hc);
        Set<String> texts = new LinkedHashSet<>();
        for (Entry e : entries(hc)) {
            if (e.data != null) {
                texts.add(e.data);
            }
        }
        StringBuilder bits = new StringBuilder();
        for (String text : texts) {
            for (char c : (text + '\0').toCharArray()) {
                bits.append(codeString(codes.get(c)));
            }
        }
        byte[] data = new byte[(bits.length() + 7) / 8];
        for (int k = 0; k < bits.length(); k++) {
            if (bits.charAt(k) == '1') {
                data[k / 8] |= (byte) (1 << (k % 8));
            }
        }
        return data;
    }
}
