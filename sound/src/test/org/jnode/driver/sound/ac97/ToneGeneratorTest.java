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
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Host-runnable test for the PCM generator of {@link ToneGenerator}.
 * <p/>
 * The generator produces the sample stream the DMA engine hands to the
 * codec. Every detail checked here was visible in a QEMU capture of the
 * driver: the leading silence that hides the codec warm-up, the envelope
 * that avoids clicks, the little endian byte order and the identical left
 * and right channels. Getting any of them wrong shows up as a click, a
 * truncated note or one silent channel.
 *
 * @author JNode contributors
 */
public class ToneGeneratorTest {

    private static final int RATE = 48000;

    @Test
    public void testRejectsInvalidParameters() {
        try {
            new ToneGenerator(RATE, 19, 100);
            fail("expected an exception for a frequency below 20 Hz");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new ToneGenerator(RATE, 10001, 100);
            fail("expected an exception for a frequency above 10000 Hz");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new ToneGenerator(0, 1000, 100);
            fail("expected an exception for a sample rate of 0");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new ToneGenerator(RATE, 1000, 0);
            fail("expected an exception for a duration of 0");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testFrameCounts() {
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 100);
        // 30ms of silence in front, 100ms of tone. 100ms at 48kHz is 4800
        // frames.
        assertEquals(RATE / 33, gen.getPrimingFrames());
        assertEquals(RATE / 100 * 10, gen.getToneFrames());
        assertEquals(4800, gen.getToneFrames());
        assertEquals(gen.getPrimingFrames() + gen.getToneFrames(),
            gen.getTotalFrames());
        // Chunks of about 25ms.
        assertTrue(gen.getFramesPerChunk() >= 32);
        assertTrue(gen.getFramesPerChunk() <= RATE / 40 + 1);
        // The generator never produces fewer frames than it announced.
        int produced = 0;
        final byte[] buf = new byte[gen.getFramesPerChunk()
            * AC97Constants.PCM_FRAME_SIZE];
        int count;
        while ((count = gen.fill(buf, gen.getFramesPerChunk())) > 0) {
            produced += count;
        }
        assertEquals(gen.getTotalFrames(), produced);
    }

    @Test
    public void testLeadingSilence() {
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 100);
        // The first activation of the codec voice warps the first few
        // milliseconds of a stream, so the tone starts with digital silence.
        assertEquals(0, gen.sampleAt(0));
        assertEquals(0, gen.sampleAt(1));
        assertEquals(0, gen.sampleAt(gen.getPrimingFrames() - 1));
    }

    @Test
    public void testAttackAndReleaseEnvelope() {
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 1000);
        final int tone = gen.getToneFrames();
        final int attack = tone / 4 < RATE / 66 ? tone / 4 : RATE / 66;
        final int release = tone / 4 < RATE / 50 ? tone / 4 : RATE / 50;
        // Ramp up over ~15ms from zero, no click at the start.
        assertEquals(0, gen.sampleAt(gen.getPrimingFrames()));
        final int midAttack = gen.getPrimingFrames() + attack / 2;
        // The sample at the middle of the attack is half amplitude; its
        // sign only depends on which half of the square wave it falls in.
        final int midAmplitude = Math.abs(gen.sampleAt(midAttack));
        assertTrue("attack midpoint must grow towards half amplitude, was "
            + midAmplitude, midAmplitude > ToneGenerator.AMPLITUDE / 2 - 100);
        assertTrue("attack midpoint must stay below full amplitude, was "
            + midAmplitude, midAmplitude < ToneGenerator.AMPLITUDE / 2 + 100);
        // Full amplitude in the middle of the tone.
        assertEquals(ToneGenerator.AMPLITUDE, gen.sampleAt(gen.getPrimingFrames() + tone / 2));
        // Ramp down over ~20ms back to zero, no click at the end either.
        final int tail = gen.getPrimingFrames() + tone - release / 2;
        final int tailAmplitude = Math.abs(gen.sampleAt(tail));
        assertTrue("release midpoint must already be below half amplitude, was "
            + tailAmplitude,
            tailAmplitude > ToneGenerator.AMPLITUDE / 2 - 100
                && tailAmplitude < ToneGenerator.AMPLITUDE / 2 + 100);
        assertTrue("the last sample of a tone must be close to silence",
            Math.abs(gen.sampleAt(gen.getTotalFrames() - 1)) < ToneGenerator.AMPLITUDE / 10);
    }

    @Test
    public void testSquareWaveShape() {
        final ToneGenerator gen = new ToneGenerator(RATE, 440, 100);
        final int halfPeriod = gen.getHalfPeriodFrames();
        assertEquals(RATE / (2 * 440), halfPeriod);
        // Past the 15ms attack ramp the polarity switches are full amplitude
        // and the period is exactly two half periods long.
        final int start = gen.getPrimingFrames();
        final int attack = Math.min(gen.getToneFrames() / 4, RATE / 66);
        // The tone alternates polarity every half period. The first half
        // period is still inside the attack ramp, so its samples grow from
        // zero; the second one is already negative.
        for (int i = 1; i < halfPeriod; i++) {
            assertTrue("positive half period at offset " + i,
                gen.sampleAt(start + i) > 0);
        }
        for (int i = 0; i < halfPeriod; i++) {
            assertTrue("negative half period at offset " + i,
                gen.sampleAt(start + halfPeriod + i) < 0);
        }
        final int sustain = start + attack + halfPeriod;
        assertEquals(ToneGenerator.AMPLITUDE, gen.sampleAt(sustain));
        assertEquals(-ToneGenerator.AMPLITUDE, gen.sampleAt(sustain + halfPeriod));
        assertEquals(ToneGenerator.AMPLITUDE, gen.sampleAt(sustain + 2 * halfPeriod));
    }

    @Test
    public void testByteEncoding() {
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 100);
        final byte[] chunk = new byte[gen.getFramesPerChunk()
            * AC97Constants.PCM_FRAME_SIZE];
        // Skip the priming silence, then read the first generated frame.
        final int priming = gen.getPrimingFrames();
        while (gen.getRemainingFrames() > priming + gen.getToneFrames() - 1) {
            gen.fill(chunk, gen.getFramesPerChunk());
        }
        final int frame = priming;
        final int ofs = 0;
        // AC'97 wants 16-bit signed little endian, left sample first.
        final int expected = gen.sampleAt(frame);
        assertEquals((byte) (expected & 0xFF), chunk[ofs]);
        assertEquals((byte) ((expected >> 8) & 0xFF), chunk[ofs + 1]);
        // Left and right channel carry the same sample.
        assertEquals(chunk[ofs], chunk[ofs + 2]);
        assertEquals(chunk[ofs + 1], chunk[ofs + 3]);
        // A positive sample must have a zero high byte, a negative one a
        // 0xFF high byte; a swapped order would show up here.
        if (expected >= 0) {
            assertTrue("positive sample must have a zero sign byte",
                (chunk[ofs + 1] & 0x80) == 0);
        } else {
            assertTrue("negative sample must be sign extended",
                (chunk[ofs + 1] & 0x80) != 0);
        }
    }

    @Test
    public void testBothChannelsAreNeverSilent() {
        // One silent channel is inaudible in a boot log but obvious to a
        // listener: the right channel must carry the same samples.
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 300);
        final byte[] chunk = new byte[gen.getFramesPerChunk()
            * AC97Constants.PCM_FRAME_SIZE];
        int count;
        while ((count = gen.fill(chunk, gen.getFramesPerChunk())) > 0) {
            for (int i = 0; i < count; i++) {
                final int ofs = i * AC97Constants.PCM_FRAME_SIZE;
                final int left = (chunk[ofs] & 0xFF) | (chunk[ofs + 1] << 8);
                final int right = (chunk[ofs + 2] & 0xFF) | (chunk[ofs + 3] << 8);
                assertEquals(left, right);
            }
        }
    }

    @Test
    public void testFillClampsToDestinationCapacity() {
        // A caller that asks for more frames than the destination array can
        // hold must not overflow it; the generator clamps to what fits.
        // (The playTone loop that hands over the remaining frame count used
        // to rely on this and threw ArrayIndexOutOfBoundsException on the
        // first note it tried to generate.)
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 100);
        final byte[] small = new byte[10 * AC97Constants.PCM_FRAME_SIZE];
        final int count = gen.fill(small, gen.getRemainingFrames());
        assertEquals(10, count);
        assertEquals(gen.getRemainingFrames() + 10, gen.getTotalFrames());
        // A request for more frames than the tone has left is clamped
        // instead of failing.
        final byte[] big = new byte[gen.getFramesPerChunk()
            * AC97Constants.PCM_FRAME_SIZE];
        int produced = 10;
        int n;
        while ((n = gen.fill(big, gen.getRemainingFrames())) > 0) {
            produced += n;
        }
        assertEquals(gen.getTotalFrames(), produced);
    }

    @Test
    public void testFillReturnsZeroAtTheEnd() {
        final ToneGenerator gen = new ToneGenerator(RATE, 1000, 20);
        final byte[] buf = new byte[gen.getFramesPerChunk()
            * AC97Constants.PCM_FRAME_SIZE];
        int n;
        int total = 0;
        while ((n = gen.fill(buf, gen.getFramesPerChunk())) > 0) {
            total += n;
        }
        assertEquals(gen.getTotalFrames(), total);
        // Draining an exhausted generator is a no-op, not an error.
        assertEquals(0, gen.fill(buf, gen.getFramesPerChunk()));
        assertEquals(0, gen.getRemainingFrames());
    }

    @Test
    public void testAmplitudeStaysInRange() {
        final ToneGenerator gen = new ToneGenerator(RATE, 20, 200);
        final int total = gen.getTotalFrames();
        for (int i = 0; i < total; i++) {
            final int sample = gen.sampleAt(i);
            assertTrue("sample out of range: " + sample, sample >= -32768
                && sample <= 32767);
        }
    }
}
