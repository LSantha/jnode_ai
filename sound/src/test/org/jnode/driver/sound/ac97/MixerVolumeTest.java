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
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Host-runnable test for the mixer volume arithmetic of {@link MixerVolume}.
 * <p/>
 * A volume register that is encoded or decoded wrongly shows up as a muted
 * channel, a swapped stereo image or a level that jumps by 50 dB at a time,
 * none of which the driver can detect on its own.
 *
 * @author JNode contributors
 */
public class MixerVolumeTest {

    @Test
    public void testZeroDbRegisterLayout() {
        // The reset value of the master volume register this driver writes:
        // both channels at 0 dB, not muted.
        final MixerVolume volume = MixerVolume.fromRegister(0x0000);
        assertEquals(0, volume.getLeftSteps());
        assertEquals(0, volume.getRightSteps());
        assertFalse(volume.isMuted());
        assertEquals(0, volume.toRegister());
        assertEquals(0.0, volume.getLeftDb(), 0.001);
    }

    @Test
    public void testPcmOutVolumeLayout() {
        // The volume the driver writes into the PCM path register: unmuted,
        // 8 steps of attenuation per channel.
        final MixerVolume volume = MixerVolume.fromRegister(
            AC97Constants.NAM_PCM_OUT_DEFAULT);
        assertEquals(8, volume.getLeftSteps());
        assertEquals(8, volume.getRightSteps());
        assertFalse(volume.isMuted());
        assertEquals(-12.0, volume.getLeftDb(), 0.001);
        // Eight steps at 1.5 dB is -12 dB, not the 0 dB of a zero
        // attenuation, so the PCM default and 0 dB are different values.
        assertTrue(AC97Constants.NAM_PCM_OUT_DEFAULT != AC97Constants.NAM_VOLUME_0DB);
    }

    @Test
    public void testMuteBit() {
        final MixerVolume muted = MixerVolume.fromRegister(
            AC97Constants.NAM_VOLUME_MUTE);
        assertTrue(muted.isMuted());
        assertEquals(0, muted.getLeftSteps());
        assertEquals(0, muted.getRightSteps());
        assertEquals(0x8000, muted.toRegister());
        // Muting does not move the level.
        final MixerVolume loud = new MixerVolume(4, 4, false);
        assertEquals(0x0404, loud.toRegister());
        assertTrue(loud.mute(true).isMuted());
        assertEquals(4, loud.mute(true).getLeftSteps());
        assertFalse(loud.mute(true).mute(false).isMuted());
    }

    @Test
    public void testLeftAndRightDoNotOverlap() {
        // Left lives in bits 13:8, right in bits 5:0; a wrong shift would
        // make one channel control the other.
        final MixerVolume left = new MixerVolume(31, 0, false);
        assertEquals(0x1F00, left.toRegister());
        assertEquals(31, MixerVolume.fromRegister(0x1F00).getLeftSteps());
        assertEquals(0, MixerVolume.fromRegister(0x1F00).getRightSteps());
        final MixerVolume right = new MixerVolume(0, 31, false);
        assertEquals(0x001F, right.toRegister());
        assertEquals(0, MixerVolume.fromRegister(0x001F).getLeftSteps());
        assertEquals(31, MixerVolume.fromRegister(0x001F).getRightSteps());
        // Asymmetric volumes survive the round trip.
        final MixerVolume both = new MixerVolume(3, 25, false);
        assertEquals(3, MixerVolume.fromRegister(both.toRegister()).getLeftSteps());
        assertEquals(25, MixerVolume.fromRegister(both.toRegister()).getRightSteps());
    }

    @Test
    public void testRoundTrip() {
        for (int left = 0; left <= AC97Constants.NAM_VOLUME_MAX_STEPS; left += 5) {
            for (int right = 0; right <= AC97Constants.NAM_VOLUME_MAX_STEPS;
                 right += 7) {
                for (int m = 0; m < 2; m++) {
                    final MixerVolume volume = new MixerVolume(left, right,
                        m != 0);
                    final int register = volume.toRegister();
                    final MixerVolume decoded = MixerVolume.fromRegister(
                        register);
                    assertEquals("left", left, decoded.getLeftSteps());
                    assertEquals("right", right, decoded.getRightSteps());
                    assertEquals("muted", m != 0, decoded.isMuted());
                }
            }
        }
    }

    @Test
    public void testUnsupportedBitsAreIgnored() {
        // A 6 bit codec that sets bit 6 or 14 decodes as 0 dB; the 5 bit
        // field this driver programs is what survives.
        final MixerVolume volume = MixerVolume.fromRegister(0x4040);
        assertEquals(0, volume.getLeftSteps());
        assertEquals(0, volume.getRightSteps());
    }

    @Test
    public void testPercentConversion() {
        // 100 percent is 0 dB, 0 percent is the quietest step.
        assertEquals(0, MixerVolume.percentToSteps(100));
        assertEquals(31, MixerVolume.percentToSteps(0));
        assertEquals(100, MixerVolume.stepsToPercent(0));
        assertEquals(0, MixerVolume.stepsToPercent(31));
        // Half way is about half way.
        final int half = MixerVolume.percentToSteps(50);
        assertTrue("half volume should be about 15 steps, was " + half,
            half >= 14 && half <= 16);
        // The conversion is monotonic: more percent is never quieter.
        int previous = Integer.MAX_VALUE;
        for (int percent = 0; percent <= 100; percent++) {
            final int steps = MixerVolume.percentToSteps(percent);
            assertTrue("percent " + percent + " gave " + steps,
                steps <= previous);
            previous = steps;
            // The codec has 32 steps for 100 percent, so a percentage is
            // rounded to the nearest representable step: the error must stay
            // below half a step.
            final double ideal = AC97Constants.NAM_VOLUME_MAX_STEPS
                * (100 - percent) / 100.0;
            assertTrue("percent " + percent + " gave " + steps
                + " instead of " + ideal, Math.abs(steps - ideal) <= 0.5);
        }
    }

    @Test
    public void testInvalidLevelsAreRejected() {
        try {
            new MixerVolume(-1, 0, false);
            fail("expected an exception for level -1");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new MixerVolume(0, AC97Constants.NAM_VOLUME_MAX_STEPS + 1, false);
            fail("expected an exception for a level above the maximum");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            MixerVolume.percentToSteps(101);
            fail("expected an exception for 101 percent");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            MixerVolume.percentToSteps(-1);
            fail("expected an exception for -1 percent");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testToString() {
        // The command prints the volume, so the text has to name the state.
        assertTrue(new MixerVolume(0, 0, false).toString().contains("100%"));
        assertTrue(new MixerVolume(31, 31, false).toString().contains("0%"));
        assertTrue(new MixerVolume(0, 0, true).toString().contains("muted"));
        // A different level per channel is printed per channel.
        final String text = new MixerVolume(0, 31, false).toString();
        assertTrue(text.contains("left"));
        assertTrue(text.contains("right"));
    }
}
