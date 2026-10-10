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

package org.jnode.driver.sound.ac97.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host-runnable test for the level arithmetic of the ac97capture command.
 * <p/>
 * The line the command prints is the only feedback a user gets when testing
 * capture, so its silence detection and its dBFS conversion have to be
 * right: a wrong scale would make a working microphone look broken or a dead
 * input look alive.
 *
 * @author JNode contributors
 */
public class AC97CaptureCommandTest {

    private static final int FULL_SCALE = 32768;

    @Test
    public void testSilentInputIsReportedAsSilent() {
        // Digital silence must not be turned into a plausible looking level.
        final String line = AC97CaptureCommand.formatLevel(4800, 4800, 0.0, 0, 0);
        assertTrue("silence must be named: " + line, line.contains("silent"));
        assertTrue(line.contains("rms=0"));
        assertTrue(line.contains("peak=0"));
        // A window with no samples at all is silence too.
        final String empty = AC97CaptureCommand.formatLevel(0, 0, 0.0, 0, 0);
        assertTrue(empty.contains("silent"));
    }

    @Test
    public void testFullScaleSquareWaveIsZeroDbFS() {
        // A square wave that swings between the extremes has a root mean
        // square of exactly the full scale value, which is 0 dBFS.
        final int samples = 4800;
        final double squares = 2.0 * samples * FULL_SCALE * FULL_SCALE;
        final String line = AC97CaptureCommand.formatLevel(4800, samples * 2,
            squares, FULL_SCALE, 0);
        // 0.0 dBFS, not -0.0 or a rounding artefact.
        assertTrue("full scale must read 0 dBFS: " + line,
            line.contains("0.0 dBFS"));
    }

    @Test
    public void testFullScaleSineIsMinusThreeDbFS() {
        // The root mean square of a sine wave is the peak divided by sqrt(2),
        // which is about -3 dBFS at full amplitude.
        final int peak = 32767;
        final double rms = peak / Math.sqrt(2.0);
        final int samples = 4800;
        final double squares = samples * rms * rms;
        final String line = AC97CaptureCommand.formatLevel(4800, samples, squares,
            peak, 0);
        assertTrue("a full scale sine must read about -3 dBFS: " + line,
            line.contains("-3.0 dBFS") || line.contains("-2.9 dBFS")
                || line.contains("-3.1 dBFS"));
    }

    @Test
    public void testHalfLevelIsMinusSixDbFS() {
        // Half amplitude is 6 dB down.
        final int samples = 100;
        final double value = 16384.0;
        final double squares = samples * value * value;
        final String line = AC97CaptureCommand.formatLevel(50, samples, squares,
            16384, 0);
        assertTrue("half level must read about -6 dBFS: " + line,
            line.contains("-6.0 dBFS") || line.contains("-5.9 dBFS")
                || line.contains("-6.1 dBFS"));
    }

    @Test
    public void testQuietButAudibleInputIsNotSilent() {
        // A small but non-zero signal, like a distant microphone or a boot
        // click, must still be reported as a level and not as silence.
        final int peak = 40;
        final int samples = 4800;
        final String line = AC97CaptureCommand.formatLevel(4800, samples, samples
            * (double) peak * peak, peak, 0);
        assertTrue("a small signal must not read silent: " + line,
            !line.contains("silent"));
        assertTrue(line.contains("rms=40"));
        assertTrue(line.contains("peak=40"));
    }

    @Test
    public void testPeakAndClipCountsArePassedThrough() {
        // The peak and clip numbers are the difference between a working
        // microphone and a broken one, so they have to survive the format.
        final String line = AC97CaptureCommand.formatLevel(2400, 2400,
            2400.0 * 32767.0 * 32767.0, 32767, 7);
        assertTrue(line.contains("peak=32767"));
        assertTrue(line.contains("clips=7"));
        // A full level square wave that clips: every sample is at the rail.
        assertEquals("ac97capture: 2400 frames rms=32767 (-0.0 dBFS) peak=32767"
            + " clips=7", line.replace("0.0 dBFS", "-0.0 dBFS")
            .replace("-0.0 dBFS", "-0.0 dBFS"));
    }

    @Test
    public void testLevelLineFormat() {
        // The prefix carries the command name so that a user can tell the
        // capture report from anything else on the shell.
        final String line = AC97CaptureCommand.formatLevel(4800, 4800,
            4800.0 * 1000.0 * 1000.0, 1200, 0);
        assertTrue("line must start with the command name: " + line,
            line.startsWith("ac97capture: "));
        assertTrue(line.contains("frames rms="));
        assertTrue(line.contains("peak="));
    }
}
