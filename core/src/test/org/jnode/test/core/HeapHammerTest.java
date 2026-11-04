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
 
package org.jnode.test.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verify that heap exhaustion is delivered as a catchable OutOfMemoryError in
 * the allocating thread and that the VM remains usable afterwards.
 */
public class HeapHammerTest {

    private static final int CHUNK_SIZE = 512 * 1024 * 1024;

    private static final int BOOT_PROBE_BYTES = 1536 * 1024 * 1024;

    public static void main(String[] args) {
        if ((requestOversizedArray() == null) && (exhaustAndCatch() == null)) {
            throw new AssertionError(
                "heap exhaustion must surface as OutOfMemoryError");
        }
        if (!recoverAfterOutOfMemory()) {
            throw new AssertionError(
                "VM did not recover after a catchable OutOfMemoryError");
        }
        System.out.println("HeapHammerTest: OutOfMemoryError delivery OK");
    }

    @Test
    public void testOutOfMemoryErrorIsCatchableInAllocatingThread() {
        final Thread allocatingThread = Thread.currentThread();
        final OutOfMemoryError caught = exhaustAndCatch();

        assertNotNull("heap exhaustion must surface as OutOfMemoryError", caught);
        assertSame("the error must be caught by the allocating thread",
            allocatingThread, Thread.currentThread());

        assertTrue("VM did not recover after a catchable OutOfMemoryError",
            recoverAfterOutOfMemory());
    }

    private static OutOfMemoryError requestOversizedArray() {
        try {
            final byte[] oversized = new byte[BOOT_PROBE_BYTES];
            oversized[0] = 1;
            return null;
        } catch (OutOfMemoryError ex) {
            return ex;
        }
    }

    private static OutOfMemoryError exhaustAndCatch() {
        try {
            exhaustHeap();
            return null;
        } catch (OutOfMemoryError ex) {
            return ex;
        }
    }

    private static void exhaustHeap() {
        final List<byte[]> retained = new ArrayList<byte[]>();
        while (true) {
            retained.add(new byte[CHUNK_SIZE]);
        }
    }

    private static boolean recoverAfterOutOfMemory() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                final byte[] small = new byte[1024];
                small[0] = 1;
                return small[0] == 1;
            } catch (OutOfMemoryError ignored) {
            }
        }
        return false;
    }
}
