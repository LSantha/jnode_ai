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
    public void testStallDetection() {
        final long now = 1000000L;
        // Fresh progress is never a stall, however long ago it was, as long
        // as it is inside the timeout.
        assertFalse(AC97Core.hasStalled(now - AC97Core.STALL_TIMEOUT, now));
        assertFalse(AC97Core.hasStalled(now - (AC97Core.STALL_TIMEOUT - 1), now));
        assertFalse(AC97Core.hasStalled(now, now));
        // Past the timeout it is.
        assertTrue(AC97Core.hasStalled(now - (AC97Core.STALL_TIMEOUT + 1), now));
        assertTrue(AC97Core.hasStalled(now - 60000, now));
    }

    @Test
    public void testRecoveryBudget() {
        // A few recoveries are attempted, but a stream that never makes
        // progress through the controller is given up on rather than
        // recovered forever.
        assertFalse(AC97Core.isRecoveryExhausted(0));
        assertFalse(AC97Core.isRecoveryExhausted(1));
        assertFalse(AC97Core.isRecoveryExhausted(AC97Core.MAX_STALL_RECOVERIES - 1));
        assertTrue(AC97Core.isRecoveryExhausted(AC97Core.MAX_STALL_RECOVERIES));
        assertTrue(AC97Core.isRecoveryExhausted(AC97Core.MAX_STALL_RECOVERIES + 5));
        // The budget is small: recovering drops the buffered audio, so it
        // must not be attempted indefinitely.
        assertTrue(AC97Core.MAX_STALL_RECOVERIES <= 5);
        assertTrue(AC97Core.MAX_STALL_RECOVERIES >= 1);
    }

    @Test
    public void testDrainCondition() {
        // drain() waits for the DMA controller halted bit: it is only set
        // once the last valid descriptor has been played, which is what
        // makes the end of a stream detectable.
        assertFalse(AC97Core.isDrainComplete(0));
        assertFalse(AC97Core.isDrainComplete(AC97Constants.SR_BCIS));
        assertFalse(AC97Core.isDrainComplete(AC97Constants.SR_CELV));
        // The bits a writer clears must not look like a halt.
        assertFalse(AC97Core.isDrainComplete(AC97Constants.SR_WC_MASK));
        assertTrue(AC97Core.isDrainComplete(AC97Constants.SR_DCH));
        assertTrue(AC97Core.isDrainComplete(AC97Constants.SR_DCH
            | AC97Constants.SR_BCIS));
    }

    @Test
    public void testRemainingSamplesEmptyRing() {
        // Nothing written yet: CIV equals LVI and PICB is zero, so nothing
        // is left to play. This is also the state after the controller has
        // finished the last descriptor.
        final int[] samples = new int[ENTRIES];
        assertEquals(0, AC97Core.remainingSamples(samples, ENTRIES, 0, 0, 0));
        assertEquals(0, AC97Core.remainingSamples(samples, ENTRIES, 5, 5, 0));
        // A partially played last descriptor reports PICB as the remainder.
        assertEquals(1234, AC97Core.remainingSamples(samples, ENTRIES, 5, 5, 1234));
    }

    @Test
    public void testRemainingSamplesWithinOnePeriodOfTheRing() {
        // Descriptors 0..4 of 1024 frames each, the controller playing
        // descriptor 2 with 800 samples left in it.
        final int[] samples = samples(ENTRIES);
        assertEquals(800 + (2048 * 2), AC97Core.remainingSamples(samples,
            ENTRIES, 2, 4, 800));
        // Straight after the fill: the controller has not started yet, so
        // all five descriptors including the current one are to be played.
        assertEquals(2048 * 5, AC97Core.remainingSamples(samples, ENTRIES, 0, 4, 2048));
        // The current one is nearly done, but 3 and 4 are still untouched.
        assertEquals(1 + (2048 * 2), AC97Core.remainingSamples(samples, ENTRIES, 2, 4, 1));
    }

    @Test
    public void testRemainingSamplesAcrossTheWrap() {
        // The ring wrapped: descriptors 30, 31, 0 and 1 are valid, the
        // controller is playing 31 with 400 samples left.
        final int[] samples = samples(ENTRIES);
        assertEquals(400 + 2048 + 2048, AC97Core.remainingSamples(samples,
            ENTRIES, 31, 1, 400));
        // A partially filled last descriptor counts only its real samples.
        samples[1] = 440;
        assertEquals(400 + 2048 + 440, AC97Core.remainingSamples(samples,
            ENTRIES, 31, 1, 400));
    }

    @Test
    public void testPositionDerivedFromQueueAndRemainder() {
        // The position is what was queued minus what is still to be played,
        // which is how getPosition() recovers the played frame count from
        // the hardware registers.
        final int[] samples = samples(ENTRIES);
        final long queued = 1024L * 1024;   // just over 21s of audio
        final long remaining = AC97Core.remainingSamples(samples, ENTRIES, 7, 9,
            1024);
        assertEquals(queued - remaining / AC97Constants.PCM_CHANNELS,
            queued - remaining / AC97Constants.PCM_CHANNELS);
        // 1024 samples of the current descriptor plus descriptors 8 and 9.
        assertEquals(1024 + (2048 * 2), remaining);
        assertEquals(1024 * 1024L - (remaining / AC97Constants.PCM_CHANNELS),
            queued - remaining / AC97Constants.PCM_CHANNELS);
    }

    private static int[] samples(int entryCount) {
        final int[] samples = new int[entryCount];
        for (int i = 0; i < entryCount; i++) {
            samples[i] = BufferDescriptorList.samplesForFrames(1024);
        }
        return samples;
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
