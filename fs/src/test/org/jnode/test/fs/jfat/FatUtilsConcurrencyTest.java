/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.test.fs.jfat;

import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicReference;

import org.jnode.fs.jfat.FatUtils;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regression test for issue #942: FatUtils used to share two static
 * SimpleDateFormat instances across all threads without synchronization.
 * Each thread must now get its own formatter via a ThreadLocal, so
 * concurrent formatting cannot corrupt shared Calendar state.
 */
public class FatUtilsConcurrencyTest {

    private static final int ITERATIONS = 500;
    private static final long TIME_A = 1234567890123L;
    private static final long TIME_B = 987654321000L;

    @Test
    public void testConcurrentFTimeAndFDate() throws Exception {
        final String expectedTimeA = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S").format(TIME_A);
        final String expectedTimeB = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S").format(TIME_B);
        final String expectedDateA = new SimpleDateFormat("yyyy-MM-dd").format(TIME_A);
        final String expectedDateB = new SimpleDateFormat("yyyy-MM-dd").format(TIME_B);

        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread threadA = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < ITERATIONS; i++) {
                        assertEquals("fTime mismatch (thread A)", expectedTimeA, FatUtils.fTime(TIME_A));
                        assertEquals("fDate mismatch (thread A)", expectedDateA, FatUtils.fDate(TIME_A));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
        }, "fatutils-format-a");

        Thread threadB = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < ITERATIONS; i++) {
                        assertEquals("fTime mismatch (thread B)", expectedTimeB, FatUtils.fTime(TIME_B));
                        assertEquals("fDate mismatch (thread B)", expectedDateB, FatUtils.fDate(TIME_B));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
        }, "fatutils-format-b");

        threadA.start();
        threadB.start();
        threadA.join(30000L);
        threadB.join(30000L);

        assertNull("formatter thread threw: " + failure.get(), failure.get());
    }
}
