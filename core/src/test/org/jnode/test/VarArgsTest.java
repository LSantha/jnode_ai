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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VarArgsTest {

    private static void testString(String msg, Object... args) {
    }

    private static void testInt(String msg, int... args) {
    }

    @Test
    public void testZeroArgs() {
        testString("zero");
    }

    @Test
    public void testSingleArg() {
        testString("single", "arg1");
    }

    @Test
    public void testMultipleArgs() {
        testString("multiple", "arg1", "arg2", "arg3");
    }

    @Test
    public void testArrayPassing() {
        String[] arr = {"a", "b", "c"};
        testString("array", arr);
    }

    @Test
    public void testNullArray() {
        String[] arr = null;
        testString("null", arr);
    }

    @Test
    public void testNullElement() {
        testString("nullElem", "a", null, "c");
    }

    @Test
    public void testPrimitiveArray() {
        int[] arr = {1, 2, 3};
        testInt("primitive", arr);
    }

    @Test
    public void testPrimitiveZeroArgs() {
        testInt("primZero");
    }

    @Test
    public void testPrimitiveSingleArg() {
        testInt("primSingle", 42);
    }

    @Test
    public void testMixedTypes() {
        testString("mixed", "str", 123, 45.67, true, 'x');
    }

    @Test
    public void testVarargsArrayType() {
        Object[] args = {"a", "b"};
        assertEquals(2, args.length);
        assertArrayEquals(new Object[]{"a", "b"}, args);
    }

    @Test
    public void testVarargsToString() {
        StringBuilder sb = new StringBuilder();
        append(sb, "hello");
        assertEquals("hello", sb.toString());

        sb.setLength(0);
        append(sb, "a", "b", "c");
        assertEquals("abc", sb.toString());
    }

    private void append(StringBuilder sb, String... parts) {
        for (String p : parts) {
            sb.append(p);
        }
    }

    @Test
    public void testVarargsWithVarargs() {
        String[] combined = combine("x", "y", "z");
        assertArrayEquals(new String[]{"x", "y", "z"}, combined);
    }

    private String[] combine(String first, String... rest) {
        String[] result = new String[1 + rest.length];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    @Test
    public void testNullVarargsParameter() {
        String[] result = handleNull((String[]) null);
        assertNull(result);
    }

    private String[] handleNull(String... args) {
        return args;
    }

    @Test
    public void testEmptyArrayVsZeroArgs() {
        assertEquals(0, countArgs());
        assertEquals(0, countArgs(new String[]{}));
    }

    private int countArgs(String... args) {
        return args.length;
    }
}
