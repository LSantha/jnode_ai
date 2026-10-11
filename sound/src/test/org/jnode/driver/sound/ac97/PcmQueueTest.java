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

package org.jnode.driver.sound.ac97;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host-runnable test for {@link PcmQueue}.
 *
 * @author JNode contributors
 */
public class PcmQueueTest {

    private static final int FRAME = AC97Constants.PCM_FRAME_SIZE;

    @Test
    public void testWriteReadRoundTrip() {
        final PcmQueue queue = new PcmQueue(64);
        final byte[] src = new byte[48];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        assertEquals(48, queue.write(src, 0, src.length));
        assertEquals(48, queue.size());
        final byte[] dst = new byte[64];
        assertEquals(48, queue.read(dst, 0, dst.length));
        for (int i = 0; i < src.length; i++) {
            assertEquals("byte " + i, src[i], dst[i]);
        }
        assertTrue(queue.isEmpty());
    }

    @Test
    public void testWrapsAround() {
        // A queue that is drained while it is being filled must keep working
        // across the wrap around of the ring.
        final PcmQueue queue = new PcmQueue(16);
        final byte[] src = new byte[12];
        final byte[] dst = new byte[12];
        for (int round = 1; round <= 20; round++) {
            for (int i = 0; i < src.length; i++) {
                src[i] = (byte) (round * 10 + i);
            }
            assertEquals(12, queue.write(src, 0, 12));
            assertEquals(12, queue.read(dst, 0, 12));
            for (int i = 0; i < src.length; i++) {
                assertEquals("round " + round + " byte " + i, src[i], dst[i]);
            }
        }
    }

    @Test
    public void testBlockingWriteWithConcurrentDrain() throws Exception {
        // A write blocks while the queue is full, which is what applies
        // backpressure to a producer that outpaces the codec; a concurrent
        // reader must then get all of it, in order.
        final PcmQueue queue = new PcmQueue(32);
        final byte[] src = new byte[40];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 5);
        }
        final int[] written = new int[1];
        final Thread producer = new Thread() {
            @Override
            public void run() {
                written[0] = queue.write(src, 0, src.length);
            }
        };
        producer.start();
        final byte[] dst = new byte[40];
        int read = 0;
        while (read < dst.length) {
            final int n = queue.read(dst, read, 16);
            if (n == 0) {
                Thread.sleep(5);
            } else {
                read += n;
            }
        }
        producer.join(5000);
        assertEquals(40, written[0]);
        for (int i = 0; i < src.length; i++) {
            assertEquals("byte " + i, src[i], dst[i]);
        }
    }

    @Test
    public void testReadReturnsLessWhenEmpty() {
        final PcmQueue queue = new PcmQueue(32);
        final byte[] dst = new byte[16];
        assertEquals(0, queue.read(dst, 0, dst.length));
        final byte[] src = new byte[8];
        assertEquals(8, queue.write(src, 0, 8));
        assertEquals(8, queue.read(dst, 0, 16));
        assertEquals(0, queue.read(dst, 0, 16));
    }

    @Test
    public void testCloseStopsBlockedWrites() {
        final PcmQueue queue = new PcmQueue(FRAME * 2);
        final byte[] src = new byte[FRAME * 8];
        // Fill the queue so that the next write has to block.
        int written = 0;
        while (written < FRAME * 2) {
            written += queue.write(src, written, FRAME * 2 - written);
        }
        queue.close();
        // The blocked write returns with what it wrote, not with the full
        // length, and never hangs.
        final int n = queue.write(src, written, src.length - written);
        assertEquals("a closed queue must accept nothing more", 0, n);
        assertTrue(queue.isClosed());
        // What is queued is still readable.
        final byte[] dst = new byte[FRAME * 2];
        assertEquals(FRAME * 2, queue.read(dst, 0, dst.length));
        assertEquals(0, queue.read(dst, 0, dst.length));
    }

    @Test
    public void testRejectsTinyCapacity() {
        try {
            new PcmQueue(FRAME - 1);
            throw new AssertionError("expected an exception");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testFrameAlignedWrites() {
        // PCM data always arrives in whole frames; the queue must not
        // fragment a frame across reads in a way that loses data.
        final PcmQueue queue = new PcmQueue(64);
        final byte[] src = new byte[64];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 3);
        }
        assertEquals(64, queue.write(src, 0, 64));
        // Read in odd sizes and reassemble.
        final byte[] dst = new byte[64];
        int read = 0;
        int[] sizes = {7, 13, 4, 31, 9};
        for (int i = 0; i < sizes.length && read < 64; i++) {
            read += queue.read(dst, read, sizes[i]);
        }
        assertEquals(64, read);
        for (int i = 0; i < 64; i++) {
            assertEquals("byte " + i, src[i], dst[i]);
        }
    }
}
