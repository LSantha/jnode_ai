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

import org.junit.Test;

public class TryFinallyTest {

    @Test
    public void testFinallyOnNormalCompletion() {
        int i = 0;
        try {
            i = 1;
        } finally {
            i = 5;
        }
        assertEquals(5, i);
    }

    @Test
    public void testFinallyOnException() {
        int i = 0;
        try {
            i = 1;
            throw new RuntimeException("test");
        } catch (RuntimeException e) {
            // expected
        } finally {
            i = 5;
        }
        assertEquals(5, i);
    }

    @Test
    public void testFinallyOnReturnInTry() {
        int i = 0;
        try {
            i = 1;
            return;
        } finally {
            i = 5;
        }
    }

    @Test
    public void testFinallyOnReturnInTryWithValue() {
        final int[] result = new int[1];
        int value = runWithReturn(result);
        assertEquals(5, result[0]);
    }

    private int runWithReturn(int[] result) {
        try {
            result[0] = 1;
            return 1;
        } finally {
            result[0] = 5;
        }
    }

    @Test
    public void testFinallyOnBreak() {
        int i = 0;
        for (int j = 0; j < 1; j++) {
            try {
                i = 1;
                break;
            } finally {
                i = 5;
            }
        }
        assertEquals(5, i);
    }

    @Test
    public void testFinallyOnContinue() {
        int i = 0;
        for (int j = 0; j < 1; j++) {
            try {
                i = 1;
                continue;
            } finally {
                i = 5;
            }
        }
        assertEquals(5, i);
    }

    @Test
    public void testFinallyWithNestedTryFinally() {
        int i = 0;
        try {
            try {
                i = 1;
            } finally {
                i = 2;
            }
        } finally {
            i = 5;
        }
        assertEquals(5, i);
    }

    @Test(expected = RuntimeException.class)
    public void testFinallyWithExceptionInFinally() {
        int i = 0;
        try {
            i = 1;
        } finally {
            i = 5;
            throw new RuntimeException("from finally");
        }
    }

    @Test
    public void testMultipleFinallyBlocks() {
        int i = 0;
        try {
            try {
                i = 1;
            } finally {
                i = 2;
            }
            try {
                i = 3;
            } finally {
                i = 4;
            }
        } finally {
            i = 5;
        }
        assertEquals(5, i);
    }
}
