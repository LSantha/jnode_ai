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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host-runnable test for the playback ring arithmetic and the prebuffering
 * rule of {@link AC97Core}.
 * <p/>
 * The producer/consumer relationship between {@code LVI} and {@code CIV} is
 * what keeps audio flowing. Getting the wrap around or the "ring is full"
 * test wrong stalls playback or, worse, overwrites a descriptor the
 * controller is still reading; both were the reason two of the bugs found
 * during QEMU validation were audible.
 *
 * @author JNode contributors
 */
public class AC97CoreTest {

    private static final int ENTRIES = BufferDescriptorList.BDL_MAX_ENTRIES;

    @Test
    public void testNextIndexWraps() {
        assertEquals(1, AC97Core.nextIndex(0, ENTRIES));
        assertEquals(15, AC97Core.nextIndex(14, ENTRIES));
        assertEquals(0, AC97Core.nextIndex(31, ENTRIES));
        assertEquals(0, AC97Core.nextIndex(ENTRIES - 1, 1));
    }

    @Test
    public void testRingIsFullOnlyWhenExactlyFull() {
        // With 32 descriptors the producer may queue 31 of them before it
        // has to wait for the controller to finish one.
        for (int i = 0; i < ENTRIES - 1; i++) {
            final boolean full = AC97Core.isRingFull(i, 0, ENTRIES);
            assertFalse("unexpectedly full with LVI=" + i, full);
        }
        assertTrue(AC97Core.isRingFull(31, 0, ENTRIES));
        // Full in the middle of the ring, and across the wrap around.
        assertTrue(AC97Core.isRingFull(7, 8, ENTRIES));
        assertFalse(AC97Core.isRingFull(8, 8, ENTRIES));
        assertTrue(AC97Core.isRingFull(0, 1, ENTRIES));
    }

    @Test
    public void testInFlightCount() {
        // The count is the number of descriptors in the cyclic range
        // [CIV, LVI] inclusive: the one being played plus those still to
        // come. When CIV equals LVI exactly one descriptor is in flight.
        assertEquals(1, AC97Core.inFlightCount(0, 0, ENTRIES));
        assertEquals(1, AC97Core.inFlightCount(5, 5, ENTRIES));
        // Straight and wrapped regions between CIV and LVI.
        assertEquals(3, AC97Core.inFlightCount(2, 0, ENTRIES));
        assertEquals(8, AC97Core.inFlightCount(7, 0, ENTRIES));
        assertEquals(3, AC97Core.inFlightCount(0, 30, ENTRIES));
        assertEquals(16, AC97Core.inFlightCount(15, 0, ENTRIES));
        // A full ring reports every descriptor as in flight, which is the
        // state where isRingFull() also becomes true.
        assertEquals(ENTRIES, AC97Core.inFlightCount(31, 0, ENTRIES));
        assertEquals(ENTRIES, AC97Core.inFlightCount(7, 8, ENTRIES));
    }

    @Test
    public void testPrebufferRule() {
        // The engine is not started on the first descriptor; that is what
        // makes a cold (class loading, JIT warm up) producer drop the start
        // of the first stream after boot.
        assertFalse(AC97Core.shouldStartEngine(1, true));
        assertFalse(AC97Core.shouldStartEngine(2, true));
        assertFalse(AC97Core.shouldStartEngine(AC97Core.PREBUFFER_DESCRIPTORS - 1,
            true));
        // From four descriptors onwards, or when the stream has no more
        // data to queue, it starts.
        assertTrue(AC97Core.shouldStartEngine(AC97Core.PREBUFFER_DESCRIPTORS,
            true));
        assertTrue(AC97Core.shouldStartEngine(ENTRIES - 1, true));
        assertTrue(AC97Core.shouldStartEngine(1, false));
        assertTrue(AC97Core.shouldStartEngine(0, false));
    }

    @Test
    public void testProducerCatchesUpAfterWrap() {
        // Play back a ring wrap: the producer queues descriptors 0..4,
        // the controller consumes them one by one, and the producer must
        // never report the ring as full while it is not.
        int lvi = ENTRIES - 1;
        int civ = ENTRIES - 1;
        for (int i = 0; i < 5; i++) {
            assertFalse(AC97Core.isRingFull(lvi, civ, ENTRIES));
            lvi = AC97Core.nextIndex(lvi, ENTRIES);
        }
        assertEquals(4, lvi);
        // The controller has finished the first two of them, so the four
        // remaining (1..4) are in flight.
        civ = AC97Core.nextIndex(civ, ENTRIES);
        civ = AC97Core.nextIndex(civ, ENTRIES);
        assertEquals(4, AC97Core.inFlightCount(lvi, civ, ENTRIES));
    }
}
