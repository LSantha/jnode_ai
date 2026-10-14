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
 * Windowed level accounting for a captured PCM stream.
 * <p>
 * The levels are the only feedback a user gets when testing the capture path,
 * so they are kept here as pure arithmetic instead of inside the shell
 * command: a window that closes at the wrong time, or a full scale sample that
 * is not counted as clipped, would make a working microphone look broken and a
 * dead input look alive.
 * <p>
 * The PCM is 16 bit signed little endian stereo, as the capture engine of the
 * AC'97 codec delivers it.
 *
 * @author JNode contributors
 */
public final class CaptureLevels {

    /**
     * Full scale of a 16 bit signed sample: one more than the largest
     * positive value.
     */
    public static final int FULL_SCALE = 32768;

    /**
     * Number of samples a window must hold before it is reported.
     */
    private final int windowSamples;

    /**
     * Samples per frame, two for the stereo of the capture engine.
     */
    private final int samplesPerFrame;

    /**
     * Samples added so far, all windows together.
     */
    private long samples;

    /**
     * Sum of the squared sample values of the current window.
     */
    private double windowSquares;

    /**
     * Samples in the current window.
     */
    private int windowSamplesSeen;

    /**
     * Largest absolute sample value of the current window.
     */
    private int windowPeak;

    /**
     * Clipped samples of the current window.
     */
    private long windowClips;

    /**
     * Largest absolute sample value of the whole recording.
     */
    private int totalPeak;

    /**
     * Clipped samples of the whole recording.
     */
    private long totalClips;

    /**
     * Creates accounting for windows of the given number of frames.
     *
     * @param windowFrames frames per reported window, 100ms at 48000Hz
     * @param samplesPerFrame samples per frame of the captured stream
     */
    public CaptureLevels(int windowFrames, int samplesPerFrame) {
        if (windowFrames <= 0) {
            throw new IllegalArgumentException("window frames must be positive");
        }
        if (samplesPerFrame <= 0) {
            throw new IllegalArgumentException(
                "samples per frame must be positive");
        }
        this.windowSamples = windowFrames * samplesPerFrame;
        this.samplesPerFrame = samplesPerFrame;
    }

    /**
     * Adds the samples of a captured buffer.
     *
     * @param pcm the captured 16 bit signed little endian samples
     * @param offset offset of the first sample
     * @param length number of bytes that hold samples
     */
    public void add(byte[] pcm, int offset, int length) {
        final int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) {
            final int sample = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            // A full scale negative sample has no positive twin in 16 bits:
            // the negation of -32768 is 32768. The accumulator is an int,
            // which is wider than the sample, so the value survives and the
            // sample is counted as the peak and as a clip.
            final int abs = sample < 0 ? -sample : sample;
            windowSquares += (double) sample * sample;
            windowSamplesSeen++;
            if (abs > windowPeak) {
                windowPeak = abs;
            }
            if (abs > totalPeak) {
                totalPeak = abs;
            }
            if (abs >= FULL_SCALE - 1) {
                windowClips++;
                // The totals are kept as the samples arrive: asking for them
                // before a window closes must not report fewer clips.
                totalClips++;
            }
        }
        samples += length / 2;
    }

    /**
     * Has the current window enough samples to be reported?
     * <p>
     * The threshold is a number of frames converted to samples, so a stereo
     * window of 100ms is 2 * rate / 10 samples and not rate / 10 samples,
     * which would close a window twice as often as documented.
     *
     * @return {@code true} if {@link #takeWindow()} has something to report
     */
    public boolean isWindowComplete() {
        return windowSamplesSeen >= windowSamples;
    }

    /**
     * Does the current window hold any sample?
     * <p>
     * A window that closed exactly at the end of the stream has nothing left
     * to report, and an extra empty line would only add noise.
     *
     * @return {@code true} if {@link #takeWindow(long)} has samples to report
     */
    public boolean hasWindow() {
        return windowSamplesSeen > 0;
    }

    /**
     * Closes the current window and formats its report.
     * <p>
     * The window state is reset, so the returned line reports the samples
     * added since the previous call only.
     *
     * @param frames frames captured so far, reported for progress
     * @return the report line of the window
     */
    public String takeWindow(long frames) {
        final String line = formatLevel(frames, windowSamplesSeen,
            windowSquares, windowPeak, windowClips);
        windowSamplesSeen = 0;
        windowSquares = 0;
        windowPeak = 0;
        windowClips = 0;
        return line;
    }

    /**
     * Frames captured so far.
     *
     * @return the number of complete frames
     */
    public long getFrames() {
        return samples / samplesPerFrame;
    }

    /**
     * Peak sample of the whole recording.
     *
     * @return the largest absolute sample value seen
     */
    public int getTotalPeak() {
        return totalPeak;
    }

    /**
     * Clipped samples of the whole recording.
     *
     * @return the number of samples at or above full scale minus one
     */
    public long getTotalClips() {
        return totalClips;
    }

    /**
     * Is the recording digital silence?
     *
     * @return {@code true} if no sample ever carried a value
     */
    public boolean isSilent() {
        return totalPeak == 0;
    }

    /**
     * Formats one level report line: root mean square, its dBFS equivalent,
     * the peak sample and the number of clipped samples of the window.
     *
     * @param frames frames recorded so far
     * @param samples samples in the window
     * @param squares sum of the squared sample values of the window
     * @param peak largest absolute sample value of the window
     * @param clips clipped samples in the window
     * @return the report line
     */
    public static String formatLevel(long frames, int samples, double squares,
        int peak, long clips) {
        final double rms = samples > 0 ? Math.sqrt(squares / samples) : 0.0;
        final String level;
        if (rms < 1.0) {
            level = "silent";
        } else {
            // dBFS: the full scale root mean square of a 16 bit signal is
            // 32768, so a square wave at full level reads 0 dBFS and a
            // sine wave at full level reads -3.0 dBFS.
            final double dbfs = 20.0 * Math.log(rms / FULL_SCALE) / Math.log(10.0);
            final int whole = (int) (dbfs * 10.0);
            level = (whole / 10) + "." + Math.abs(whole % 10) + " dBFS";
        }
        return "ac97capture: " + frames + " frames rms=" + (int) rms + " ("
            + level + ") peak=" + peak + " clips=" + clips;
    }
}
