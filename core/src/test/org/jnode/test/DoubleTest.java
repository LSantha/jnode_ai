/*
 * $Id$
 *
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
 
package org.jnode.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JUnit4 host-runnable test for the double to int narrowing conversion emitted
 * by the x86 back end, see issue #501.
 * <p/>
 * Covers positive, negative and fractional values, the truncation bounds
 * (JLS 5.1.3: the result is the value closest to the operand that is not
 * greater than it), NaN and the infinities.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class DoubleTest {

    private static int d2i(double d) {
        return (int) d;
    }

    @Test
    public void testPositiveFractions() {
        assertEquals(0, d2i(0.0));
        assertEquals(0, d2i(0.4));
        assertEquals(0, d2i(0.5));
        assertEquals(0, d2i(0.6));
        assertEquals(1, d2i(1.0));
        assertEquals(1, d2i(1.1));
        assertEquals(1, d2i(1.4));
        assertEquals(1, d2i(1.5));
        assertEquals(1, d2i(1.6));
        assertEquals(1, d2i(1.9));
        assertEquals(2, d2i(2.0));
    }

    @Test
    public void testNegativeFractions() {
        assertEquals(0, d2i(-0.4));
        assertEquals(0, d2i(-0.5));
        assertEquals(-1, d2i(-1.0));
        assertEquals(-1, d2i(-1.9));
        assertEquals(-2, d2i(-2.0));
        assertEquals(-2, d2i(-2.5));
        assertEquals(-3, d2i(-3.0));
    }

    @Test
    public void testTruncationTowardZero() {
        for (int i = -100; i <= 100; i++) {
            final double exact = i + 0.5;
            final int expected = (i < 0) ? i + 1 : i;
            assertEquals("d2i(" + exact + ")", expected, d2i(exact));
        }
    }

    @Test
    public void testNaNConvertsToZero() {
        assertEquals(0, d2i(Double.NaN));
    }

    @Test
    public void testInfinities() {
        assertEquals(Integer.MAX_VALUE, d2i(Double.POSITIVE_INFINITY));
        assertEquals(Integer.MIN_VALUE, d2i(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void testOutOfRangeValuesClamp() {
        assertEquals(Integer.MAX_VALUE, d2i(1e18));
        assertEquals(Integer.MIN_VALUE, d2i(-1e18));
    }

    @Test
    public void testNarrowingRoundTrip() {
        for (int i = -50; i <= 50; i++) {
            final double d = i + 0.75;
            assertEquals("round trip failed for " + i, i, d2i(d - 0.75));
        }
    }

    @Test
    public void testCastingIsIdempotentOnIntegralValues() {
        for (int i = -1000; i <= 1000; i += 7) {
            assertEquals(i, d2i((double) i));
        }
    }

    @Test
    public void testFractionalPartIsAlwaysBelowOne() {
        for (double d = -10.0; d <= 10.0; d += 0.125) {
            final int truncated = d2i(d);
            if (d >= 0) {
                assertTrue("truncation of " + d + " must not exceed the operand", truncated <= d);
            } else {
                assertTrue("truncation of " + d + " must not go below the operand", truncated >= d);
            }
            assertTrue("truncation of " + d + " must stay within one unit",
                Math.abs(d - truncated) < 1.0);
        }
    }
}
