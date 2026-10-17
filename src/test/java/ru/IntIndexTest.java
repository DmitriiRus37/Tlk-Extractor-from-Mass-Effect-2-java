package ru;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntIndexTest {

    @Test
    void numbersKeysInOrderOfFirstOccurrence() {
        IntIndex index = new IntIndex(4);
        assertEquals(0, index.add(7));
        assertEquals(1, index.add(-1));
        assertEquals(0, index.add(7));
        assertEquals(2, index.add(Integer.MIN_VALUE));
        assertEquals(1, index.add(-1));
        assertEquals(3, index.size());
        assertEquals(Integer.MIN_VALUE, index.key(2));
    }

    @Test
    void behavesLikeALinkedHashMapAndGrowsBeyondItsExpectedSize() {
        Random random = new Random(1);
        IntIndex index = new IntIndex(10);
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        for (int i = 0; i < 50_000; i++) {
            // few distinct values (many repeats), multiples of large powers of two (collisions)
            int key = random.nextBoolean() ? random.nextInt(20_000) : random.nextInt(64) << 24;
            Integer known = expected.putIfAbsent(key, expected.size());
            assertEquals(known == null ? expected.size() - 1 : known, index.add(key));
        }
        assertEquals(expected.size(), index.size());
        int i = 0;
        for (int key : expected.keySet()) {
            assertEquals(key, index.key(i++));
        }
    }
}
