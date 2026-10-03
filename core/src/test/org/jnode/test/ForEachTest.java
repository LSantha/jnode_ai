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
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * JUnit4 host-runnable test for the enhanced for loop (for-each) over arrays
 * and Iterable collections, see issue #501.
 * <p/>
 * Covers primitive arrays, object arrays and generic collections, and verifies
 * that every element is visited exactly once and in declaration order.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 * @author Fabien DUMINY (fduminy@jnode.org)
 */
public class ForEachTest {

    @Test
    public void testIntArray() {
        final int[] a = new int[]{0, 1, 2, 3, 4, 5};

        int expected = 0;
        for (int i : a) {
            assertEquals(expected, i);
            expected++;
        }
        assertEquals("array not fully iterated", expected, a.length);
    }

    @Test
    public void testStringArray() {
        final String[] a = new String[]{"A", "B", "C", "D", "E"};

        int idxExpected = 0;
        for (String s : a) {
            assertEquals(a[idxExpected], s);
            idxExpected++;
        }
        assertEquals("array not fully iterated", idxExpected, a.length);
    }

    @Test
    public void testCollection() {
        final ArrayList<String> list = new ArrayList<String>();
        list.add("Aap");
        list.add("Noot");
        list.add("Mies");

        int idxExpected = 0;
        for (String s : list) {
            assertEquals(list.get(idxExpected), s);
            idxExpected++;
        }
        assertEquals("collection not fully iterated", idxExpected, list.size());
    }

    @Test
    public void testEmptyArray() {
        final String[] a = new String[0];

        int count = 0;
        for (String s : a) {
            assertNull("unexpected element in empty array", s);
            count++;
        }
        assertEquals(0, count);
    }

    @Test
    public void testNullElementsArePassedThrough() {
        final String[] a = new String[]{"A", null, "C"};

        final List<String> seen = new ArrayList<String>();
        for (String s : a) {
            seen.add(s);
        }
        assertEquals(3, seen.size());
        assertEquals("A", seen.get(0));
        assertNull(seen.get(1));
        assertEquals("C", seen.get(2));
    }

    @Test
    public void testArrayIsCopiedBeforeIteration() {
        final int[] a = new int[]{1, 2, 3};

        final List<Integer> seen = new ArrayList<Integer>();
        for (int i : a) {
            seen.add(Integer.valueOf(i));
            if (seen.size() == 1) {
                a[0] = 99;
            }
        }
        assertEquals(3, seen.size());
        assertEquals(1, seen.get(0).intValue());
        assertEquals(2, seen.get(1).intValue());
        assertEquals(3, seen.get(2).intValue());
    }

    @Test
    public void testIteratorOverList() {
        final List<String> list = new ArrayList<String>();
        list.add("one");
        list.add("two");
        list.add("three");

        int words = 0;
        for (String word : list) {
            assertEquals(list.get(words), word);
            words++;
        }
        assertEquals(list.size(), words);
    }
}
