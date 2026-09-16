/*
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.test.bugs;

/**
 * Verifies strictfp Math.rint behavior, which relies on the x87 FPU using
 * 53-bit double precision (PC=10) with round-to-nearest (RC=00).
 * With 64-bit extended precision the twoToThe52 trick in StrictMath.rint
 * preserves fraction bits and returns wrong results.
 */
public class RintTest {

    static int failures = 0;

    static void check(double actual, double expected, String name) {
        boolean pass;
        if (Double.isNaN(expected)) {
            pass = Double.isNaN(actual);
        } else if (expected == 0.0) {
            // Distinguish +0.0 from -0.0 via raw bits
            pass = (Double.doubleToLongBits(actual) == Double.doubleToLongBits(expected));
        } else {
            pass = (actual == expected);
        }
        if (!pass) {
            System.out.println("FAIL: " + name + " got " + actual + " expected " + expected);
            failures++;
        }
    }

    static void checkTrick(double actual, double expected, String name) {
        if (actual != expected) {
            System.out.println("FAIL: " + name + " got " + actual + " expected " + expected);
            failures++;
        }
    }

    public static void main(String[] a) {
        // The 3 mauve test_rint cases broken by extended precision
        check(Math.rint(2.3), 2.0, "rint(2.3)");
        check(Math.rint(2.7), 3.0, "rint(2.7)");
        check(Math.rint(2.5), 2.0, "rint(2.5)");

        // Banker's rounding (ties to even)
        check(Math.rint(3.5), 4.0, "rint(3.5)");
        check(Math.rint(4.5), 4.0, "rint(4.5)");
        check(Math.rint(-2.5), -2.0, "rint(-2.5)");

        // Special values must pass through unchanged
        check(Math.rint(Double.POSITIVE_INFINITY), Double.POSITIVE_INFINITY, "rint(+Inf)");
        check(Math.rint(Double.NEGATIVE_INFINITY), Double.NEGATIVE_INFINITY, "rint(-Inf)");
        check(Math.rint(Double.NaN), Double.NaN, "rint(NaN)");
        check(Math.rint(0.0), 0.0, "rint(0.0)");
        check(Math.rint(-0.0), -0.0, "rint(-0.0)");

        // The twoToThe52 trick StrictMath.rint relies on:
        // ulp(2^52) must be 1.0, so adding 2^52 rounds away the fraction.
        double t52 = (double) (1L << 52);
        checkTrick((t52 + 2.3) - t52, 2.0, "trick(2.3)");
        checkTrick((t52 + 2.7) - t52, 3.0, "trick(2.7)");
        checkTrick((t52 + 2.5) - t52, 2.0, "trick(2.5)");

        if (failures == 0) {
            System.out.println("All tests PASS");
        } else {
            System.out.println("FAILURES: " + failures);
        }
    }
}
