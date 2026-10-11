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

/**
 * One stereo volume register of the AC'97 mixer, in codec terms.
 * <p>
 * The register holds a mute bit plus a 5 bit attenuation per channel, where
 * 0 is 0 dB and every step is 1.5 dB down, so 31 steps are -46.5 dB. The
 * attenuation is stored per channel and the register has the left channel in
 * bits 13:8 and the right channel in bits 5:0.
 * <p>
 * This class is pure arithmetic over that layout so that it can be unit
 * tested without a codec; the hardware side lives in
 * {@link AC97Core#getMasterVolume} and its friends.
 *
 * @author JNode contributors
 */
public final class MixerVolume implements AC97Constants {

    /**
     * Lowest attenuation step: 0 dB, the loudest setting.
     */
    public static final int MAX_LEVEL = 0;

    /**
     * Highest attenuation step: 31 x 1.5 dB below 0 dB.
     */
    public static final int MIN_LEVEL = NAM_VOLUME_MAX_STEPS;

    private final int leftSteps;
    private final int rightSteps;
    private final boolean muted;

    /**
     * Creates a volume.
     *
     * @param leftSteps  attenuation of the left channel in 1.5 dB steps,
     *                   0 is 0 dB
     * @param rightSteps attenuation of the right channel
     * @param muted      if true the codec mutes this output
     */
    public MixerVolume(int leftSteps, int rightSteps, boolean muted) {
        if ((leftSteps < 0) || (leftSteps > MIN_LEVEL)) {
            throw new IllegalArgumentException("Invalid left level "
                + leftSteps);
        }
        if ((rightSteps < 0) || (rightSteps > MIN_LEVEL)) {
            throw new IllegalArgumentException("Invalid right level "
                + rightSteps);
        }
        this.leftSteps = leftSteps;
        this.rightSteps = rightSteps;
        this.muted = muted;
    }

    public int getLeftSteps() {
        return leftSteps;
    }

    public int getRightSteps() {
        return rightSteps;
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * Gets the attenuation of the left channel in decibels, so 8 steps read
     * -12.0 dB.
     */
    public double getLeftDb() {
        return -1.5 * leftSteps;
    }

    /**
     * Gets the attenuation of the right channel in decibels.
     */
    public double getRightDb() {
        return -1.5 * rightSteps;
    }

    /**
     * Gets the same volume with the mute flag changed.
     */
    public MixerVolume mute(boolean value) {
        return new MixerVolume(leftSteps, rightSteps, value);
    }

    /**
     * Gets the same volume with both channels changed, in codec steps.
     */
    public MixerVolume level(int left, int right) {
        return new MixerVolume(left, right, muted);
    }

    /**
     * Encodes this volume as the bits of the mixer register.
     */
    public int toRegister() {
        int value = 0;
        if (muted) {
            value |= NAM_VOLUME_MUTE;
        }
        value |= (leftSteps << NAM_VOLUME_LEFT_SHIFT)
            | (rightSteps << NAM_VOLUME_RIGHT_SHIFT);
        return value;
    }

    /**
     * Decodes a mixer register value. Bits outside the supported fields are
     * ignored, so a 6 bit codec that sets bit 6 or 14 reads back as 0 dB.
     */
    public static MixerVolume fromRegister(int value) {
        final boolean muted = (value & NAM_VOLUME_MUTE) != 0;
        final int left = (value >> NAM_VOLUME_LEFT_SHIFT) & NAM_VOLUME_MASK;
        final int right = (value >> NAM_VOLUME_RIGHT_SHIFT) & NAM_VOLUME_MASK;
        return new MixerVolume(left, right, muted);
    }

    /**
     * Converts a percentage of full volume to codec steps: 100 % is 0 dB and
     * 0 % is the quietest step.
     *
     * @param percent 0..100
     */
    public static int percentToSteps(int percent) {
        if ((percent < 0) || (percent > 100)) {
            throw new IllegalArgumentException("Invalid percentage " + percent);
        }
        // Rounded to the nearest representable step (half the divisor is
        // added before the division), so that a percentage the codec cannot
        // express exactly is rounded rather than always made quieter.
        return (((100 - percent) * NAM_VOLUME_MAX_STEPS) + 50) / 100;
    }

    /**
     * Converts codec steps to a percentage of full volume, the inverse of
     * {@link #percentToSteps}.
     *
     * @param steps 0..NAM_VOLUME_MAX_STEPS
     */
    public static int stepsToPercent(int steps) {
        if ((steps < 0) || (steps > MIN_LEVEL)) {
            throw new IllegalArgumentException("Invalid level " + steps);
        }
        return 100 - (((steps * 100) + (NAM_VOLUME_MAX_STEPS / 2))
            / NAM_VOLUME_MAX_STEPS);
    }

    @Override
    public String toString() {
        if (muted) {
            return "muted";
        }
        final int leftPercent = stepsToPercent(leftSteps);
        final int rightPercent = stepsToPercent(rightSteps);
        if (leftPercent == rightPercent) {
            return leftPercent + "%";
        }
        return "left " + leftPercent + "% right " + rightPercent + "%";
    }
}
