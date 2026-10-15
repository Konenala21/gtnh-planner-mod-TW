package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Fractional order keys: the npm scheme's own examples, and keys that always land between their neighbours. */
class OrderKeysTest {

    @Test
    void theSchemesOwnExamples() {
        final String[][] cases = { { null, null, "a0" }, { null, "a0", "Zz" }, { null, "Zz", "Zy" },
            { "a0", null, "a1" }, { "a1", null, "a2" }, { "a0", "a1", "a0V" }, { "a1", "a2", "a1V" },
            { "a0V", "a1", "a0l" }, { "Zz", "a0", "ZzV" }, { "Zz", "a1", "a0" }, { null, "Y00", "Xzzz" },
            { "bzz", null, "c000" }, { "a0", "a0V", "a0G" }, { "a0", "a0G", "a08" }, { "b125", "b129", "b127" },
            { "a0", "a1V", "a1" }, { "Zz", "a01", "a0" }, { null, "a0V", "a0" }, { null, "b999", "b99" } };
        for (final String[] c : cases) assertEquals(c[2], OrderKeys.between(c[0], c[1]), c[0] + " .. " + c[1]);
        assertEquals(List.of("a0", "a1", "a2"), OrderKeys.sequence(3));
    }

    @Test
    void whatIsNotAKeyIsRefused() {
        assertFalse(OrderKeys.valid(""));
        assertFalse(OrderKeys.valid("a"));
        assertFalse(OrderKeys.valid("a00"));
        assertFalse(OrderKeys.valid("x"));
        assertFalse(OrderKeys.valid("1"));
        assertTrue(OrderKeys.valid("a0"));
        assertTrue(OrderKeys.valid("Zz"));
        assertThrows(IllegalArgumentException.class, () -> OrderKeys.between("a1", "a0"));
        assertThrows(IllegalArgumentException.class, () -> OrderKeys.between("a1", "a1"));
        assertThrows(IllegalArgumentException.class, () -> OrderKeys.between("", null));
    }

    @Test
    void aKeyAlwaysLandsBetween() {
        final Random random = new Random(7);
        final List<String> keys = new ArrayList<>(List.of("a0"));
        for (int n = 0; n < 2000; n++) {
            final int at = random.nextInt(keys.size() + 1);
            final String a = at == 0 ? null : keys.get(at - 1), b = at == keys.size() ? null : keys.get(at);
            final String k = OrderKeys.between(a, b);
            assertTrue(OrderKeys.valid(k), k);
            if (a != null) assertTrue(a.compareTo(k) < 0, a + " < " + k);
            if (b != null) assertTrue(k.compareTo(b) < 0, k + " < " + b);
            keys.add(at, k);
        }
    }
}
