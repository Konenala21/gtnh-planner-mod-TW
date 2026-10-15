package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Fractional order keys, the scheme the shared plan's "a0" keys come from (the npm {@code fractional-indexing}
 * package's; the website's task board must write the same): strings that sort as plain text, so a thing moved between
 * two others takes one new key between theirs and nothing else changes. A key is an integer part (a head letter saying
 * how many base-62 digits follow: "a0" is the first, "a1" the
 * next, "Zz" the one before "a0") and an optional fraction with no trailing zero ("a0V" sits between "a0" and "a1").
 */
public final class OrderKeys {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = DIGITS.charAt(0);
    /** The smallest integer part, which nothing can go before. */
    private static final String SMALLEST = "A" + String.valueOf(ZERO)
        .repeat(26);

    private OrderKeys() {}

    /** The first key of an empty list. */
    public static final String FIRST = "a0";

    /** Whether a key is one this scheme writes (anything else sorts, but nothing can be put next to it). */
    public static boolean valid(@Nullable final String key) {
        if (key == null || key.isEmpty() || key.equals(SMALLEST)) return false;
        final int n = integerLength(key.charAt(0));
        if (n < 0 || n > key.length()) return false;
        for (int i = 1; i < key.length(); i++) if (DIGITS.indexOf(key.charAt(i)) < 0) return false;
        return key.length() == n || key.charAt(key.length() - 1) != ZERO;
    }

    /**
     * A key between {@code a} and {@code b} (null for no bound on that side): after the last when {@code b} is null,
     * before the first when {@code a} is. Throws when either is not {@link #valid} or {@code a} is not below {@code b}.
     */
    public static String between(@Nullable final String a, @Nullable final String b) {
        if (a != null && !valid(a)) throw new IllegalArgumentException("not an order key: " + a);
        if (b != null && !valid(b)) throw new IllegalArgumentException("not an order key: " + b);
        if (a != null && b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        if (a == null) {
            if (b == null) return FIRST;
            final String ib = integerPart(b), fb = b.substring(ib.length());
            if (ib.equals(SMALLEST)) return ib + midpoint("", fb);
            if (ib.compareTo(b) < 0) return ib;
            final String res = decrement(ib);
            if (res == null) throw new IllegalArgumentException("nothing goes before " + b);
            return res;
        }
        if (b == null) {
            final String ia = integerPart(a), fa = a.substring(ia.length());
            final String i = increment(ia);
            return i == null ? ia + midpoint(fa, null) : i;
        }
        final String ia = integerPart(a), fa = a.substring(ia.length());
        final String ib = integerPart(b), fb = b.substring(ib.length());
        if (ia.equals(ib)) return ia + midpoint(fa, fb);
        final String i = increment(ia);
        if (i == null) throw new IllegalArgumentException("nothing goes after " + a);
        if (i.compareTo(b) < 0) return i;
        return ia + midpoint(fa, null);
    }

    /** {@code n} keys in order, for a list whose keys are lost or clash: "a0", "a1", ... */
    public static List<String> sequence(final int n) {
        final List<String> out = new ArrayList<>(n);
        String last = null;
        for (int i = 0; i < n; i++) {
            last = between(last, null);
            out.add(last);
        }
        return out;
    }

    /** A fraction strictly between two (digits after the integer part; "" is the lowest, null no upper bound). */
    private static String midpoint(final String a, @Nullable final String b) {
        if (b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        if (!a.isEmpty() && a.charAt(a.length() - 1) == ZERO
            || b != null && !b.isEmpty() && b.charAt(b.length() - 1) == ZERO)
            throw new IllegalArgumentException("trailing zero");
        if (b != null) {
            // The common prefix stays, `a` read as padded with zeros.
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : ZERO) == b.charAt(n)) n++;
            if (n > 0) return b.substring(0, n) + midpoint(n < a.length() ? a.substring(n) : "", b.substring(n));
        }
        final int digitA = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        final int digitB = b != null ? DIGITS.indexOf(b.charAt(0)) : DIGITS.length();
        if (digitB - digitA > 1) return String.valueOf(DIGITS.charAt((int) Math.round(0.5 * (digitA + digitB))));
        // The first digits are next to each other.
        if (b != null && b.length() > 1) return b.substring(0, 1);
        return DIGITS.charAt(digitA) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    /** How long a key's integer part is, from its head; -1 for a head no key has. */
    private static int integerLength(final char head) {
        if (head >= 'a' && head <= 'z') return head - 'a' + 2;
        if (head >= 'A' && head <= 'Z') return 'Z' - head + 2;
        return -1;
    }

    private static String integerPart(final String key) {
        return key.substring(0, integerLength(key.charAt(0)));
    }

    /** The next integer part, or null past the largest. */
    @Nullable
    private static String increment(final String x) {
        final char head = x.charAt(0);
        final char[] digits = x.substring(1)
            .toCharArray();
        boolean carry = true;
        for (int i = digits.length - 1; carry && i >= 0; i--) {
            final int d = DIGITS.indexOf(digits[i]) + 1;
            if (d == DIGITS.length()) digits[i] = ZERO;
            else {
                digits[i] = DIGITS.charAt(d);
                carry = false;
            }
        }
        if (!carry) return head + new String(digits);
        if (head == 'Z') return "a" + ZERO;
        if (head == 'z') return null;
        final char h = (char) (head + 1);
        final String rest = new String(digits);
        // Past "a" each head holds one more digit; up to it, one fewer.
        return h > 'a' ? h + rest + ZERO : h + rest.substring(0, rest.length() - 1);
    }

    /** The integer part before, or null before the smallest. */
    @Nullable
    private static String decrement(final String x) {
        final char head = x.charAt(0);
        final char[] digits = x.substring(1)
            .toCharArray();
        final char top = DIGITS.charAt(DIGITS.length() - 1);
        boolean borrow = true;
        for (int i = digits.length - 1; borrow && i >= 0; i--) {
            final int d = DIGITS.indexOf(digits[i]) - 1;
            if (d == -1) digits[i] = top;
            else {
                digits[i] = DIGITS.charAt(d);
                borrow = false;
            }
        }
        if (!borrow) return head + new String(digits);
        if (head == 'a') return "Z" + top;
        if (head == 'A') return null;
        final char h = (char) (head - 1);
        final String rest = new String(digits);
        return h < 'Z' ? h + rest + top : h + rest.substring(0, rest.length() - 1);
    }
}
