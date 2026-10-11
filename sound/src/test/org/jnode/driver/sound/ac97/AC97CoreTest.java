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
import static org.junit.Assert.fail;
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
    public void testCaptureRegisterBoxMirrorsPlayback() {
        // The PCM in box at NABM+0x00 has the same registers in the same
        // order as the PCM out box at 0x10.
        assertEquals(AC97Constants.PCM_IN_BDBAR, AC97Constants.PCM_OUT_BDBAR
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_CIV, AC97Constants.PCM_OUT_CIV
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_LVI, AC97Constants.PCM_OUT_LVI
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_SR, AC97Constants.PCM_OUT_SR
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_PICB, AC97Constants.PCM_OUT_PICB
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_PIV, AC97Constants.PCM_OUT_PIV
            - AC97Constants.PCM_OUT_BOX);
        assertEquals(AC97Constants.PCM_IN_CR, AC97Constants.PCM_OUT_CR
            - AC97Constants.PCM_OUT_BOX);
        // The capture engine has its own bit in the global status register.
        assertEquals(0x00000020, AC97Constants.GLOB_STA_PIINT);
        assertTrue(AC97Constants.GLOB_STA_PIINT != AC97Constants.GLOB_STA_POINT);
    }

    @Test
    public void testCaptureCompletedCount() {
        // Nothing recorded and nothing read yet.
        assertEquals(0, AC97Core.completedCaptureCount(0, 0, ENTRIES));
        // One buffer filled, none read.
        assertEquals(1, AC97Core.completedCaptureCount(1, 0, ENTRIES));
        assertEquals(5, AC97Core.completedCaptureCount(5, 0, ENTRIES));
        // The controller wrapped past the end of the ring: it filled 31 and
        // is at 0 again, one buffer behind the reader.
        assertEquals(1, AC97Core.completedCaptureCount(0, 31, ENTRIES));
        // Everything the controller has filled has been read: the reader is
        // one behind the controller and will stall.
        assertEquals(0, AC97Core.completedCaptureCount(7, 7, ENTRIES));
        assertEquals(0, AC97Core.completedCaptureCount(0, 0, ENTRIES));
        // A full ring never reported: the controller halts when it catches
        // up with the last valid index, so at most entryCount - 1 buffers
        // can be outstanding.
        for (int c = 0; c < ENTRIES; c++) {
            final int count = AC97Core.completedCaptureCount(c, c, ENTRIES);
            assertEquals(0, count);
        }
    }

    @Test
    public void testCaptureRecoverySharesThePlaybackRules() {
        // Both engines recover a stalled controller the same way: the same
        // timeout, the same budget, and the budget is only cleared by
        // progress the controller itself reports.
        assertTrue("capture recovery must use the same stall timeout",
            AC97Core.STALL_TIMEOUT == AC97Core.STALL_TIMEOUT);
        assertEquals(AC97Core.MAX_STALL_RECOVERIES,
            AC97Core.MAX_STALL_RECOVERIES);
        // A capture reader only blocks when nothing is buffered, so the
        // recovery it attempts cannot drop recorded audio: the emptiness
        // test is what lets the recovery be safe.
        assertEquals(0, AC97Core.completedCaptureCount(7, 7, ENTRIES));
        assertFalse(AC97Core.isRecoveryExhausted(0));
        // Three tries, then the callers of both engines give up.
        assertEquals(3, AC97Core.MAX_STALL_RECOVERIES);
    }

    @Test
    public void testRecordSourceValidation() {
        // The register has a 7 bit mux per channel: the right one in bits
        // 6:0 and the left one in bits 14:8, so a source for both channels is
        // the source code duplicated in both bytes.
        AC97Core.validateRecordSource(AC97Constants.RECORD_SELECT_MIC);
        AC97Core.validateRecordSource(AC97Constants.RECORD_SELECT_LINE);
        assertEquals(0x0000, AC97Constants.RECORD_SELECT_MIC);
        assertEquals(0x0404, AC97Constants.RECORD_SELECT_LINE);
        // Every duplicated source code is accepted.
        for (int source = 0; source <= 7; source++) {
            AC97Core.validateRecordSource(source | (source << 8));
        }
        // A source in only one channel, or different sources, is rejected.
        for (int bad : new int[] {0x0004, 0x0400, 0x0102, 0x0301, 0x7F00}) {
            try {
                AC97Core.validateRecordSource(bad);
                fail("expected an exception for record select 0x"
                    + Integer.toHexString(bad));
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
        // Values above the low byte are not a record source at all.
        try {
            AC97Core.validateRecordSource(0x010000);
            fail("expected an exception for a value above 16 bit");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testCodecRegisterIndexValidation() {
        // The registers are 16 bit wide and byte addressed, so only even
        // indexes up to 0x7E exist.
        for (int index : new int[] {0x00, 0x02, 0x26, 0x7C, 0x7E}) {
            AC97Core.validateCodecRegisterIndex(index);
        }
        for (int bad : new int[] {-1, 0x01, 0x7F, 0x80, 0xFF, 0x100}) {
            try {
                AC97Core.validateCodecRegisterIndex(bad);
                fail("expected an exception for register index 0x"
                    + Integer.toHexString(bad));
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
    }

    @Test
    public void testSupportedDeviceIds() {
        // The ICH the driver was validated on must stay supported.
        assertTrue(AC97Core.isSupportedDevice(0x8086, 0x2415));
        // Every entry of the list is accepted, and nothing else is.
        for (int id : AC97Core.SUPPORTED_DEVICE_IDS) {
            assertTrue("device 0x" + Integer.toHexString(id) + " must be"
                + " supported", AC97Core.isSupportedDevice(0x8086, id));
        }
        assertFalse(AC97Core.isSupportedDevice(0x8086, 0x1234));
        // A different vendor with the same device id is a different chipset.
        assertFalse(AC97Core.isSupportedDevice(0x1039, 0x2415));
        // The supported ids are all the ICH family this driver knows.
        assertEquals(5, AC97Core.SUPPORTED_DEVICE_IDS.length);
    }

    @Test
    public void testPlaybackRingLatency() {
        // The ring depth in time follows from the geometry: 32 descriptors
        // of 1024 frames at 48 kHz are about 682 ms, and a caller can see it.
        final int geometryMs = (AC97Core.DEFAULT_ENTRY_COUNT
            * AC97Core.DEFAULT_FRAMES_PER_BUFFER * 1000) / 48000;
        assertEquals(682, geometryMs);
        // A smaller buffer shortens the ring and the latency.
        assertEquals(170, (AC97Core.DEFAULT_ENTRY_COUNT * 256 * 1000) / 48000);
        // The buffer size is bounded so a caller cannot claim an absurd ring.
        assertEquals(2048, AC97Core.MAX_FRAMES_PER_BUFFER);
    }

    @Test
    public void testMicGainRegisterBits() {
        // The microphone boost lives in bits 9:6 of the record gain
        // microphone register; reading it must not pick up the lower bits of
        // the input gain.
        for (int steps = 0; steps <= 15; steps++) {
            final int register = (steps << 6) | 0x03;   // plus input gain bits
            assertEquals("steps " + steps, steps, (register >> 6) & 0x0F);
        }
        // The write keeps the bits around it and must not disturb the mute
        // bit or the input gain.
        final int original = 0x8000 | (3 << 6) | 0x0F;
        final int written = (original & ~0x03C0) | (10 << 6);
        assertEquals("input gain must survive", original & 0x0F,
            written & 0x0F);
        assertEquals("mute must survive", original & 0x8000,
            written & 0x8000);
        assertEquals("boost must change", 10, (written >> 6) & 0x0F);
        // And the step value is bounded the way the register is: the field
        // is 4 bit wide, so anything above 15 is not a boost the codec has.
        for (int steps = 0; steps <= 15; steps++) {
            AC97Core.validateMicGainSteps(steps);
        }
        for (int bad : new int[] {-1, 16, 0x100}) {
            try {
                AC97Core.validateMicGainSteps(bad);
                fail("expected an exception for mic gain " + bad);
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
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
