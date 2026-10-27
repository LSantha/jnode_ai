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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * JUnit4 host-runnable test for the enhanced for loop (for-each) over arrays
 * and Iterable collections, see issue #501.
 * <p/>
 * Covers primitive arrays, object arrays and generic collections, and verifies
 * that every element is visited exactly once and in declaration order.
 * <p/>
 * The shared input data is created in {@link #setUp()} and released in
 * {@link #tearDown()} so that every test iteration starts from a clean state.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 * @author Fabien DUMINY (fduminy@jnode.org)
 */
public class ForEachTest {

    private int[] intArray;
    private String[] stringArray;
    private List<String> collection;

    @Before
    public void setUp() {
        intArray = new int[]{0, 1, 2, 3, 4, 5};
        stringArray = new String[]{"A", "B", "C", "D", "E"};
        collection = new ArrayList<String>();
        collection.add("Aap");
        collection.add("Noot");
        collection.add("Mies");
    }

    @After
    public void tearDown() {
        intArray = null;
        stringArray = null;
        collection.clear();
        collection = null;
    }

    @Test
    public void testIntArray() {
        int expected = 0;
        for (int i : intArray) {
            assertEquals(expected, i);
            expected++;
        }
        assertEquals("array not fully iterated", expected, intArray.length);
    }

    @Test
    public void testStringArray() {
        int idxExpected = 0;
        for (String s : stringArray) {
            assertEquals(stringArray[idxExpected], s);
            idxExpected++;
        }
        assertEquals("array not fully iterated", idxExpected, stringArray.length);
    }

    @Test
    public void testCollection() {
        int idxExpected = 0;
        for (String s : collection) {
            assertEquals(collection.get(idxExpected), s);
            idxExpected++;
        }
        assertEquals("collection not fully iterated", idxExpected, collection.size());
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
        int words = 0;
        for (String word : collection) {
            assertEquals(collection.get(words), word);
            words++;
        }
        assertEquals(collection.size(), words);
    }
}
