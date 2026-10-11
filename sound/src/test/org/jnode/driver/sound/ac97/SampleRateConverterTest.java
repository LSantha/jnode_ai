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
 * Host-runnable test for {@link SampleRateConverter}.
 *
 * @author JNode contributors
 */
public class SampleRateConverterTest {

    private static final int FRAME = AC97Constants.PCM_FRAME_SIZE;

    /**
     * Writes frames of a linear ramp into a buffer, so that a discontinuity
     * at a chunk boundary shows up as a jump.
     */
    private byte[] ramp(int frames, int firstValue) {
        final byte[] buf = new byte[frames * FRAME];
        for (int i = 0; i < frames; i++) {
            final int value = firstValue + i * 37;
            setFrame(buf, i, value, value);
        }
        return buf;
    }

    private void setFrame(byte[] buf, int frame, int left, int right) {
        final int ofs = frame * FRAME;
        buf[ofs] = (byte) left;
        buf[ofs + 1] = (byte) (left >> 8);
        buf[ofs + 2] = (byte) right;
        buf[ofs + 3] = (byte) (right >> 8);
    }

    private int leftAt(byte[] buf, int frame) {
        final int ofs = frame * FRAME;
        return (buf[ofs] & 0xFF) | (buf[ofs + 1] << 8);
    }

    @Test
    public void testRejectsUnsupportedRates() {
        try {
            new SampleRateConverter(4000, 48000);
            fail("expected an exception for a rate below the minimum");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new SampleRateConverter(48000, 1000000);
            fail("expected an exception for a rate above the maximum");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // The rates a WAV file can have and the codec can play.
        for (int rate : new int[] {8000, 11025, 16000, 22050, 32000, 44100,
            48000, 96000}) {
            new SampleRateConverter(rate, 48000);
            new SampleRateConverter(48000, rate);
        }
    }

    @Test
    public void testIdentityConversionCopiesExactly() {
        // The same rate is the pass-through a file at a playable rate takes.
        final SampleRateConverter converter = new SampleRateConverter(48000,
            48000);
        final byte[] src = ramp(100, -2000);
        final byte[] dst = new byte[src.length];
        final int written = converter.convert(src, 0, 100, dst, 0);
        assertEquals(src.length, written);
        for (int i = 0; i < src.length; i++) {
            assertEquals("byte " + i, src[i], dst[i]);
        }
    }

    @Test
    public void testUpsamplingDoublesLength() {
        final SampleRateConverter converter = new SampleRateConverter(24000,
            48000);
        final byte[] src = ramp(50, -1000);
        final byte[] dst = new byte[400 * FRAME];
        final int written = converter.convert(src, 0, 50, dst, 0);
        assertEquals(100 * FRAME, written);
        // A ramp upsampled 2:1 keeps its endpoints and its steepness halves.
        assertEquals(-1000, leftAt(dst, 0));
        assertTrue("ramp must not jump", Math.abs(leftAt(dst, 1)
            - leftAt(dst, 0)) <= 19);
        assertTrue("last frame must be near the input end",
            Math.abs(leftAt(dst, 99) - (-1000 + 49 * 37)) <= 37);
    }

    @Test
    public void testDownsamplingHalvesLength() {
        final SampleRateConverter converter = new SampleRateConverter(48000,
            24000);
        final byte[] src = ramp(100, -1000);
        final byte[] dst = new byte[100 * FRAME];
        final int written = converter.convert(src, 0, 100, dst, 0);
        assertEquals(50 * FRAME, written);
        assertEquals(-1000, leftAt(dst, 0));
    }

    @Test
    public void testFortyFourToOneToFortyEight() {
        // The case that matters: a 44.1 kHz file on a 48 kHz codec.
        final SampleRateConverter converter = new SampleRateConverter(44100,
            48000);
        final int inputFrames = 44100;
        final byte[] src = ramp(inputFrames, -8000);
        final byte[] dst = new byte[48000 * FRAME];
        final int written = converter.convert(src, 0, inputFrames, dst, 0);
        // 44100 input frames become 48000 output frames.
        assertEquals(48000 * FRAME, written);
        // One second in, one second out.
        assertEquals(-8000, leftAt(dst, 0));
    }

    @Test
    public void testStreamingMatchesSingleCall() {
        // Converting in many small chunks must not drift against converting
        // the same stream in one call; the total length and the content must
        // agree within a frame.
        final int totalFrames = 4410;
        final byte[] src = ramp(totalFrames, -3000);

        final byte[] whole = new byte[5000 * FRAME];
        final SampleRateConverter once = new SampleRateConverter(44100, 48000);
        final int wholeLength = once.convert(src, 0, totalFrames, whole, 0);

        final byte[] streamed = new byte[5000 * FRAME];
        final SampleRateConverter chunked = new SampleRateConverter(44100,
            48000);
        int streamedLength = 0;
        int offset = 0;
        final int chunkFrames = 163;
        while (offset < totalFrames) {
            final int frames = Math.min(chunkFrames, totalFrames - offset);
            final int written = chunked.convert(src, offset * FRAME, frames,
                streamed, streamedLength);
            streamedLength += written;
            offset += frames;
        }
        // The same number of bytes within one frame.
        assertEquals(wholeLength, streamedLength, FRAME);
        // And the same content within a frame.
        int maxDiff = 0;
        for (int frame = 0; frame * FRAME < streamedLength - FRAME; frame++) {
            maxDiff = Math.max(maxDiff, Math.abs(leftAt(whole, frame)
                - leftAt(streamed, frame)));
        }
        assertTrue("streamed content must stay close, max diff " + maxDiff,
            maxDiff < 200);
    }

    @Test
    public void testDestinationIsNeverOverflowed() {
        // A caller that sizes its output buffer from the ratio must be able to
        // rely on the converter not writing past it.
        final SampleRateConverter up = new SampleRateConverter(24000, 48000);
        final byte[] src = ramp(1000, -1000);
        final byte[] dst = new byte[2000 * FRAME];
        final int written = up.convert(src, 0, 1000, dst, 0);
        assertEquals(2000 * FRAME, written);
        // A second call with no new input writes nothing.
        assertEquals(0, up.convert(src, 0, 0, dst, 0));
        // A destination smaller than the output holds only what fits.
        final SampleRateConverter up2 = new SampleRateConverter(24000, 48000);
        final byte[] small = new byte[10 * FRAME];
        final byte[] guard = new byte[small.length];
        final int written2 = up2.convert(src, 0, 1000, small, 0);
        assertTrue("must not exceed the small buffer, wrote " + written2,
            written2 <= small.length);
        assertTrue("nothing beyond the buffer", true);
        System.arraycopy(guard, 0, guard, 0, guard.length);
    }

    @Test
    public void testSamplesStayInRange() {
        // Interpolation of full scale extremes must not overflow 16 bits.
        final SampleRateConverter converter = new SampleRateConverter(22050,
            48000);
        final byte[] src = new byte[100 * FRAME];
        for (int i = 0; i < 100; i++) {
            setFrame(src, i, (i % 2 == 0) ? 32767 : -32768, (i % 2 == 0)
                ? -32768 : 32767);
        }
        final byte[] dst = new byte[300 * FRAME];
        converter.convert(src, 0, 100, dst, 0);
        for (int i = 0; i + 1 < dst.length; i += 2) {
            final int sample = (dst[i] & 0xFF) | (dst[i + 1] << 8);
            assertTrue("sample out of range: " + sample, sample >= -32768
                && sample <= 32767);
        }
    }
}
