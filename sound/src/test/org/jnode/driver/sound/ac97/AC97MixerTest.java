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

import org.junit.Test;

/**
 * Host-runnable test for the mixing arithmetic of {@link AC97Mixer}.
 *
 * @author JNode contributors
 */
public class AC97MixerTest {

    private static final int FRAME = AC97Constants.PCM_FRAME_SIZE;

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

    private int rightAt(byte[] buf, int frame) {
        final int ofs = frame * FRAME;
        return (buf[ofs + 2] & 0xFF) | (buf[ofs + 3] << 8);
    }

    @Test
    public void testMixIntoEmptyDestination() {
        // A voice playing into an empty destination is a copy, at full gain.
        final byte[] dst = new byte[4 * FRAME];
        final byte[] src = new byte[4 * FRAME];
        for (int i = 0; i < 4; i++) {
            setFrame(src, i, -1000 + i * 100, 500 - i * 100);
        }
        AC97Mixer.mix(dst, 0, src, 0, 4, 0);
        for (int i = 0; i < 4; i++) {
            assertEquals(src[i * FRAME], dst[i * FRAME]);
        }
        assertEquals(-1000, leftAt(dst, 0));
        assertEquals(500, rightAt(dst, 0));
        assertEquals(-700, leftAt(dst, 3));
        assertEquals(200, rightAt(dst, 3));
    }

    @Test
    public void testTwoVoicesAddUp() {
        // Two voices at full gain add, which is what makes an overlap of a
        // tone and a file sound louder rather than one of them winning.
        final byte[] dst = new byte[FRAME];
        final byte[] a = new byte[FRAME];
        final byte[] b = new byte[FRAME];
        setFrame(a, 0, 1000, -2000);
        setFrame(b, 0, 300, 700);
        AC97Mixer.mix(dst, 0, a, 0, 1, 0);
        AC97Mixer.mix(dst, 0, b, 0, 1, 0);
        assertEquals(1300, leftAt(dst, 0));
        assertEquals(-1300, rightAt(dst, 0));
    }

    @Test
    public void testMixClampsToFullScale() {
        // Two full scale voices must not wrap around into the opposite
        // polarity, which is what a byte level mix would do.
        final byte[] dst = new byte[FRAME];
        final byte[] a = new byte[FRAME];
        final byte[] b = new byte[FRAME];
        setFrame(a, 0, 32767, -32768);
        setFrame(b, 0, 32767, -32768);
        AC97Mixer.mix(dst, 0, a, 0, 1, 0);
        AC97Mixer.mix(dst, 0, b, 0, 1, 0);
        assertEquals(32767, leftAt(dst, 0));
        assertEquals(-32768, rightAt(dst, 0));
    }

    @Test
    public void testGainAttenuates() {
        // A voice at half the steps is roughly half amplitude, and the two
        // channels are attenuated the same way.
        final byte[] dst = new byte[FRAME];
        final byte[] src = new byte[FRAME];
        setFrame(src, 0, 20000, -20000);
        final int half = AC97Constants.NAM_VOLUME_MAX_STEPS / 2;
        AC97Mixer.mix(dst, 0, src, 0, 1, half);
        // Half the steps is half the amplitude, not half of that.
        assertEquals("left must be roughly half", 20000 / 2, leftAt(dst, 0), 200);
        assertEquals("right must be roughly half", -20000 / 2, rightAt(dst, 0), 200);
    }

    @Test
    public void testSilentGainSilences() {
        // The maximum attenuation is silent, so a muted voice contributes
        // nothing.
        final byte[] dst = new byte[FRAME];
        final byte[] src = new byte[FRAME];
        setFrame(src, 0, 20000, -20000);
        AC97Mixer.mix(dst, 0, src, 0, 1, AC97Constants.NAM_VOLUME_MAX_STEPS);
        assertEquals(0, leftAt(dst, 0));
        assertEquals(0, rightAt(dst, 0));
    }

    @Test
    public void testRejectsInvalidGain() {
        final byte[] dst = new byte[FRAME];
        final byte[] src = new byte[FRAME];
        try {
            AC97Mixer.mix(dst, 0, src, 0, 1, -1);
            throw new AssertionError("expected an exception for gain -1");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            AC97Mixer.mix(dst, 0, src, 0, 1,
                AC97Constants.NAM_VOLUME_MAX_STEPS + 1);
            throw new AssertionError("expected an exception for gain 32");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testMixDoesNotTouchFramesPastTheCount() {
        // The destination holds more frames than are mixed; the frames past
        // the count must keep their original content, because the mixer
        // mixes chunk after chunk into the same buffer.
        final byte[] dst = new byte[4 * FRAME];
        final byte[] src = new byte[4 * FRAME];
        for (int i = 0; i < 4; i++) {
            setFrame(src, i, 1000 * (i + 1), -1000 * (i + 1));
            setFrame(dst, i, 111, -111);   // a marker in every frame
        }
        AC97Mixer.mix(dst, 0, src, 0, 2, 0);
        // Mixing is additive, so the mixed frames are the marker plus the
        // source, and the untouched frames are the marker alone.
        assertEquals(111 + 1000, leftAt(dst, 0));
        assertEquals(111 + 2000, leftAt(dst, 1));
        assertEquals(111, leftAt(dst, 2));
        assertEquals(-111, rightAt(dst, 2));
        assertEquals(111, leftAt(dst, 3));
    }
}
