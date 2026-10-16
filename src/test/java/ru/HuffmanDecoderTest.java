package ru;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuffmanDecoderTest {

    /**
     * Builds a random full binary tree with the given number of leaves in TLK form: node 0 is the
     * root, leaves are {@code TlkFile.leafId(c)}, and {@code '\0'} is one of them.
     */
    private static List<TlkFile.HuffmanNode> randomTree(Random random, int leaves) {
        List<int[]> nodes = new ArrayList<>();
        nodes.add(new int[2]);
        List<int[]> openSlots = new ArrayList<>();
        openSlots.add(new int[]{0, 0});
        openSlots.add(new int[]{0, 1});
        // grow by turning a random open slot into an inner node until leaves - 1 inner nodes exist
        while (nodes.size() < leaves - 1) {
            int[] slot = openSlots.remove(random.nextInt(openSlots.size()));
            nodes.get(slot[0])[slot[1]] = nodes.size();
            openSlots.add(new int[]{nodes.size(), 0});
            openSlots.add(new int[]{nodes.size(), 1});
            nodes.add(new int[2]);
        }
        for (int i = 0; i < openSlots.size(); i++) {
            int[] slot = openSlots.get(i);
            char c = i == 0 ? '\0' : (char) ('A' + i);
            nodes.get(slot[0])[slot[1]] = TlkFile.leafId(c);
        }
        List<TlkFile.HuffmanNode> tree = new ArrayList<>();
        for (int[] n : nodes) {
            tree.add(new TlkFile.HuffmanNode(n[0], n[1]));
        }
        return tree;
    }

    @Test
    void tablesGiveTheSameResultAsBitByBit() {
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            List<TlkFile.HuffmanNode> tree = randomTree(random, 2 + random.nextInt(120));
            byte[] data = new byte[1 + random.nextInt(64)];
            random.nextBytes(data);
            HuffmanDecoder bitwise = new HuffmanDecoder(tree, data, false);
            HuffmanDecoder tables = new HuffmanDecoder(tree, data, true);
            StringBuilder expected = new StringBuilder();
            StringBuilder actual = new StringBuilder();
            for (int offset = 0; offset <= data.length * 8 + 1; offset++) {
                boolean terminated = bitwise.decode(offset, expected);
                assertEquals(terminated, tables.decode(offset, actual), "offset " + offset);
                assertEquals(expected.toString(), actual.toString(), "offset " + offset);
            }
        }
    }

    @Test
    void singleNodeTree() {
        // root with '\0' on the left and 'a' on the right: bits 1,1,0 -> "aa"
        List<TlkFile.HuffmanNode> tree = new ArrayList<>();
        tree.add(new TlkFile.HuffmanNode(TlkFile.leafId('\0'), TlkFile.leafId('a')));
        byte[] data = {0b0000_0011};
        for (boolean useTables : new boolean[]{false, true}) {
            StringBuilder text = new StringBuilder();
            assertTrue(new HuffmanDecoder(tree, data, useTables).decode(0, text));
            assertEquals("aa", text.toString());
            assertTrue(new HuffmanDecoder(tree, data, useTables).decode(1, text));
            assertEquals("a", text.toString());
        }
    }

    @Test
    void missingTerminatorIsReported() {
        List<TlkFile.HuffmanNode> tree = new ArrayList<>();
        tree.add(new TlkFile.HuffmanNode(TlkFile.leafId('\0'), TlkFile.leafId('a')));
        byte[] data = {(byte) 0xff, (byte) 0xff};
        for (boolean useTables : new boolean[]{false, true}) {
            StringBuilder text = new StringBuilder();
            assertFalse(new HuffmanDecoder(tree, data, useTables).decode(3, text));
            assertEquals("aaaaaaaaaaaaa", text.toString());
            assertFalse(new HuffmanDecoder(tree, data, useTables).decode(16, text));
            assertEquals("", text.toString());
        }
    }
}
