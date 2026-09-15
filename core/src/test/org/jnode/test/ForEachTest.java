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

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ForEachTest {

    private List<String> testList;
    private int[] testIntArray;
    private String[] testStringArray;

    @Before
    public void setUp() {
        testList = new ArrayList<String>();
        testList.add("Aap");
        testList.add("Noot");
        testList.add("Mies");

        testIntArray = new int[]{0, 1, 2, 3, 4, 5};
        testStringArray = new String[]{"A", "B", "C", "D", "E"};
    }

    @After
    public void tearDown() {
        testList = null;
        testIntArray = null;
        testStringArray = null;
    }

    @Test
    public void testIntArray() {
        int expected = 0;
        for (int i : testIntArray) {
            assertEquals(expected, i);
            expected++;
        }
        assertEquals("array not fully iterated", expected, testIntArray.length);
    }

    @Test
    public void testStringArray() {
        int idxExpected = 0;
        for (String s : testStringArray) {
            assertEquals(testStringArray[idxExpected], s);
            idxExpected++;
        }
        assertEquals("array not fully iterated", idxExpected, testStringArray.length);
    }

    @Test
    public void testCollection() {
        int idxExpected = 0;
        for (String s : testList) {
            assertEquals(testList.get(idxExpected), s);
            idxExpected++;
        }
        assertEquals("collection not fully iterated", idxExpected, testList.size());
    }
}
