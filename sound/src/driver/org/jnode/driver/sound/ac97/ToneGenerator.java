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
 * Generates 16-bit signed stereo PCM data for a single square wave tone with
 * a short attack and release envelope, in the sample format of the AC'97 DAC.
 * <p>
 * The generator is pure arithmetic and does not touch any hardware, which
 * makes the PCM encoding (little endian byte order, identical left and right
 * channels, silence in front of the tone, envelope shape, amplitude clamping)
 * unit testable. It feeds {@link AC97API#write} through
 * {@link AC97Utils#playTone}.
 *
 * @author JNode contributors
 */
public final class ToneGenerator implements AC97Constants {

    /**
     * Lowest tone frequency.
     */
    public static final int MIN_FREQUENCY = 20;

    /**
     * Highest tone frequency.
     */
    public static final int MAX_FREQUENCY = 10000;

    /**
     * Amplitude of the square wave in 16-bit samples.
     */
    public static final int AMPLITUDE = 6000;

    /**
     * The chunk size generated in one step, about 25ms of audio at 48kHz.
     */
    private final int framesPerChunk;

    /**
     * Number of digital silence frames in front of the tone.
     */
    private final int primingFrames;

    /**
     * Number of frames of the tone itself.
     */
    private final int toneFrames;

    /**
     * Length of the attack ramp in frames, about 15ms at 48kHz.
     */
    private final int attackFrames;

    /**
     * Length of the release ramp in frames, about 20ms at 48kHz.
     */
    private final int releaseFrames;

    /**
     * Number of frames per half period of the square wave, rounded down.
     */
    private final int halfPeriodFrames;

    /**
     * Number of frames of PCM data generated so far.
     */
    private int frame;

    /**
     * Creates a generator for a tone of the given frequency and duration.
     *
     * @param rate        sample rate in Hz
     * @param frequencyHz tone frequency, 20..10000 Hz
     * @param durationMs  duration in milliseconds
     */
    public ToneGenerator(int rate, int frequencyHz, int durationMs) {
        if ((frequencyHz < MIN_FREQUENCY) || (frequencyHz > MAX_FREQUENCY)) {
            throw new IllegalArgumentException("Invalid frequency "
                + frequencyHz);
        }
        if ((rate <= 0) || (durationMs <= 0)) {
            throw new IllegalArgumentException("Invalid rate or duration");
        }
        this.framesPerChunk = Math.max(32, rate / 40);
        this.primingFrames = rate / 33;
        this.toneFrames = (int) (((long) rate * durationMs) / 1000);
        this.attackFrames = Math.min(toneFrames / 4, rate / 66);
        this.releaseFrames = Math.min(toneFrames / 4, rate / 50);
        this.halfPeriodFrames = Math.max(1, rate / (2 * frequencyHz));
        this.frame = 0;
    }

    /**
     * Gets the number of frames generated in one {@link #fill} call.
     */
    public final int getFramesPerChunk() {
        return framesPerChunk;
    }

    /**
     * Gets the total number of frames of this tone, priming silence included.
     */
    public final int getTotalFrames() {
        return primingFrames + toneFrames;
    }

    /**
     * Gets the number of frames still to be generated.
     */
    public final int getRemainingFrames() {
        return Math.max(0, getTotalFrames() - frame);
    }

    /**
     * Gets the number of frames of the tone itself, without the priming
     * silence in front of it.
     */
    public final int getToneFrames() {
        return toneFrames;
    }

    /**
     * Gets the number of 16-bit samples per half period of the square wave.
     */
    public final int getHalfPeriodFrames() {
        return halfPeriodFrames;
    }

    /**
     * Gets the number of leading silence frames.
     */
    public final int getPrimingFrames() {
        return primingFrames;
    }

    /**
     * Generates PCM data into a destination array. The number of frames is
     * clamped to both the remaining frames of the tone and the capacity of
     * the destination array, so a caller can never overflow the buffer, no
     * matter which of the two it passes.
     *
     * @param dst    destination array
     * @param frames requested number of frames
     * @return the number of frames actually generated
     */
    public final int fill(byte[] dst, int frames) {
        final int capacity = dst.length / PCM_FRAME_SIZE;
        final int count = Math.min(Math.min(frames, capacity),
            getRemainingFrames());
        for (int i = 0; i < count; i++) {
            final int sample = sampleAt(frame);
            final int ofs = i * PCM_FRAME_SIZE;
            // Little endian 16-bit, left and right channel.
            dst[ofs] = (byte) sample;
            dst[ofs + 1] = (byte) (sample >> 8);
            dst[ofs + 2] = (byte) sample;
            dst[ofs + 3] = (byte) (sample >> 8);
            frame++;
        }
        return count;
    }

    /**
     * Gets the 16-bit sample value of a given frame of this tone, envelope
     * and all.
     *
     * @param frame frame index, counting from the start of the priming
     *              silence
     */
    public final int sampleAt(int frame) {
        final int toneFrame = frame - primingFrames;
        int sample;
        if (toneFrame < 0) {
            sample = 0;
        } else {
            final int polarity = (((toneFrame / halfPeriodFrames) & 1) == 0)
                ? 1 : -1;
            double gain = 1.0;
            if (toneFrame < attackFrames) {
                gain = (double) toneFrame / attackFrames;
            } else if (toneFrame >= toneFrames - releaseFrames) {
                gain = (double) (toneFrames - toneFrame) / releaseFrames;
            }
            sample = (int) (AMPLITUDE * gain * polarity);
        }
        if (sample > 32767) {
            sample = 32767;
        } else if (sample < -32768) {
            sample = -32768;
        }
        return sample;
    }
}
