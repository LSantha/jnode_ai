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

import org.junit.Before;
import org.junit.Test;

/**
 * Host-runnable test for the windowed level accounting of a captured stream.
 * <p>
 * Two things have to hold for the capture report to be of any use: a window
 * must close after the number of frames it was asked for, so the reports line
 * up with the wall clock, and a full scale sample must be counted as the peak
 * and as a clip, without the sign of the extreme sample value getting lost.
 *
 * @author JNode contributors
 */
public class CaptureLevelsTest {

    /**
     * The captured stream is 16 bit stereo, like the capture engine delivers.
     */
    private static final int SAMPLES_PER_FRAME = 2;

    /**
     * Frames per window: 100ms at 48000Hz.
     */
    private static final int WINDOW_FRAMES = 4800;

    private CaptureLevels levels;

    /**
     * A 16 bit signed little endian stereo sample pair of the given values.
     */
    private static byte[] frames(int left, int right, int count) {
        final byte[] pcm = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            pcm[i * 4] = (byte) left;
            pcm[i * 4 + 1] = (byte) (left >> 8);
            pcm[i * 4 + 2] = (byte) right;
            pcm[i * 4 + 3] = (byte) (right >> 8);
        }
        return pcm;
    }

    @Before
    public void setUp() {
        levels = new CaptureLevels(WINDOW_FRAMES, SAMPLES_PER_FRAME);
    }

    @Test
    public void testWindowClosesAfterFramesNotSamples() {
        // A 100ms window is 4800 frames, which is 9600 samples. Counting
        // samples against the frame count would close the window twice as
        // often, because a stereo frame holds two samples.
        levels.add(frames(1000, 1000, WINDOW_FRAMES / 2), 0,
            WINDOW_FRAMES * 2);
        assertFalse("half a window is not a window", levels.isWindowComplete());

        // The remaining half: the window is complete now.
        levels.add(frames(1000, 1000, WINDOW_FRAMES / 2), 0,
            WINDOW_FRAMES * 2);
        assertTrue("a full window must close", levels.isWindowComplete());
        assertEquals(WINDOW_FRAMES, levels.getFrames());
    }

    @Test
    public void testReportedFramesFollowTheWindow() {
        // The frames of a window are reported as captured so far, and the
        // windows that follow start from the same total.
        for (int window = 0; window < 3; window++) {
            levels.add(frames(500, 500, WINDOW_FRAMES), 0, WINDOW_FRAMES * 4);
            assertTrue("window " + window + " must close",
                levels.isWindowComplete());
            assertTrue(levels.hasWindow());
            final String line = levels.takeWindow((window + 1) * WINDOW_FRAMES);
            assertTrue("the window reports the total frames: " + line,
                line.startsWith("ac97capture: " + (window + 1) * WINDOW_FRAMES
                    + " frames"));
            assertFalse("a taken window starts over",
                levels.isWindowComplete());
        }
        assertEquals(3 * WINDOW_FRAMES, levels.getFrames());
    }

    @Test
    public void testFullScaleNegativeSampleIsAClip() {
        // -32768 has no positive twin in 16 bits. Dropping its sign, or
        // wrapping it to a negative peak, would report a clipped input as
        // clean.
        levels.add(new byte[] {(byte) 0x00, (byte) 0x80}, 0, 2);
        assertEquals("a full scale negative sample must be the peak",
            -Short.MIN_VALUE, levels.getTotalPeak());
        assertEquals("a full scale negative sample must clip", 1,
            levels.getTotalClips());
        final String line = levels.takeWindow(1);
        assertTrue("peak must show the full scale value: " + line,
            line.contains("peak=32768"));
        assertTrue("and it must be counted as a clip: " + line,
            line.contains("clips=1"));
    }

    @Test
    public void testFullScalePositiveSampleIsAClip() {
        // 32767 is one step below the negative extreme, and still the rail of
        // the converter, so it counts as a clip too.
        levels.add(new byte[] {(byte) 0xFF, (byte) 0x7F}, 0, 2);   // 0x7FFF
        assertEquals(32767, levels.getTotalPeak());
        assertEquals(1, levels.getTotalClips());
    }

    @Test
    public void testJustBelowFullScaleIsNotAClip() {
        levels.add(new byte[] {(byte) 0xFE, (byte) 0x7F}, 0, 2);   // 0x7FFE
        assertEquals(32766, levels.getTotalPeak());
        assertEquals("a sample below the rail is not a clip", 0,
            levels.getTotalClips());
    }

    @Test
    public void testWindowStatisticsResetBetweenWindows() {
        // A loud window must not bleed into the quiet one after it.
        levels.add(frames(12000, 12000, WINDOW_FRAMES), 0, WINDOW_FRAMES * 4);
        levels.takeWindow(WINDOW_FRAMES);

        levels.add(frames(300, 300, WINDOW_FRAMES), 0, WINDOW_FRAMES * 4);
        final String line = levels.takeWindow(2 * WINDOW_FRAMES);
        assertTrue("the quiet window must not carry the loud peak: " + line,
            line.contains("peak=300"));
        assertTrue(line.contains("rms=300"));
        // The totals of the whole recording still know about the loud part.
        assertEquals(12000, levels.getTotalPeak());
        assertEquals(2 * WINDOW_FRAMES, levels.getFrames());
    }

    @Test
    public void testSilenceIsDetectedOverTheWholeRecording() {
        assertTrue("nothing recorded is silence", levels.isSilent());
        levels.add(frames(0, 0, WINDOW_FRAMES), 0, WINDOW_FRAMES * 4);
        assertTrue("zeros are digital silence", levels.isSilent());

        levels.add(frames(1, 0, 1), 0, 4);
        assertFalse("a single non-zero sample is not silence",
            levels.isSilent());
    }

    @Test
    public void testOddLengthBufferIsCountedInSamples() {
        // A buffer that ends in the middle of a sample would read the high
        // byte of a sample as a sample of its own; only whole samples count.
        // Four whole samples across two odd buffers: 1, 2 and 5, 6.
        levels.add(new byte[] {1, 0, 2, 0, 3}, 0, 5);
        levels.add(new byte[] {0x05, 0x00, 0x06, 0x00, 0x07}, 0, 5);
        assertEquals(2, levels.getFrames());
        // The truncated bytes are dropped, not turned into garbage peaks.
        assertEquals(6, levels.getTotalPeak());
    }

    @Test
    public void testWindowIsUnknownBeforeItCloses() {
        // A stream shorter than the window can never complete it, so the
        // leftover samples are reported on their own.
        levels.add(frames(700, 700, 10), 0, 40);
        assertFalse(levels.isWindowComplete());
        assertTrue("the leftover window holds the samples", levels.hasWindow());
        final String line = levels.takeWindow(10);
        assertTrue(line.contains("peak=700"));
        assertFalse("a taken window starts over", levels.hasWindow());
    }
}
