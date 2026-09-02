package org.jnode.test.bugs;

/** Test for float/double to int/long conversion with NaN/infinity/overflow. */
public class ConversionTest {

    static int failures = 0;

    static void check(long actual, long expected, String name) {
        if (actual != expected) {
            System.out.println("FAIL: " + name + " got " + actual + " expected " + expected);
            failures++;
        }
    }

    static void check(int actual, int expected, String name) {
        if (actual != expected) {
            System.out.println("FAIL: " + name + " got " + actual + " expected " + expected);
            failures++;
        }
    }

    public static void main(String[] a) {
        // NaN conversion
        check((long) Double.NaN, 0L, "(long)NaN");
        check((int) Float.NaN, 0, "(int)NaNf");

        // Positive infinity
        check((long) Double.POSITIVE_INFINITY, Long.MAX_VALUE, "(long)+Inf");
        check((int) Float.POSITIVE_INFINITY, Integer.MAX_VALUE, "(int)+INff");

        // Negative infinity
        check((long) Double.NEGATIVE_INFINITY, Long.MIN_VALUE, "(long)-Inf");
        check((int) Float.NEGATIVE_INFINITY, Integer.MIN_VALUE, "(int)-INff");

        // Normal values still work
        check((long) 1.5, 1L, "(long)1.5");
        check((int) 1.5f, 1, "(int)1.5f");
        check((long) -1.5, -1L, "(long)-1.5");
        check((int) -1.5f, -1, "(int)-1.5f");

        // Zero
        check((long) 0.0, 0L, "(long)0.0");
        check((long) -0.0, 0L, "(long)-0.0");

        // Overflow
        check((long) 1e20, Long.MAX_VALUE, "(long)1e20 overflow");
        check((long) -1e20, Long.MIN_VALUE, "(long)-1e20 underflow");
        check((int) 1e20f, Integer.MAX_VALUE, "(int)1e20f overflow");
        check((int) -1e20f, Integer.MIN_VALUE, "(int)-1e20f underflow");

        // MAX/MIN_VALUE boundaries
        check((long) Long.MAX_VALUE, Long.MAX_VALUE, "(long)Long.MAX_VALUE");
        check((long) Long.MIN_VALUE, Long.MIN_VALUE, "(long)Long.MIN_VALUE");

        if (failures == 0) {
            System.out.println("All tests PASS");
        } else {
            System.out.println("FAILURES: " + failures);
        }
    }
}
