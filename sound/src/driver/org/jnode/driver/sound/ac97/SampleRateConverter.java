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
 * Converts 16-bit signed stereo PCM from one sample rate to another with
 * linear interpolation, streaming.
 * <p>
 * The codec only plays the rates of the AC'97 specification, so a 44.1 kHz
 * file on a codec without variable rate audio used to be unplayable. The
 * converter resamples it to a rate the codec accepts instead.
 * <p>
 * The class is pure arithmetic over byte arrays so that it can be unit
 * tested without a codec. It converts between chunks and keeps its
 * fractional position across them, so a stream can be converted in whatever
 * sizes the caller reads it in; the output length is derived from the ratio
 * and may differ from the input length by up to one frame per call.
 *
 * @author JNode contributors
 */
public final class SampleRateConverter {

    /**
     * Supported input and output rates; the same range the codec accepts.
     */
    public static final int MIN_RATE = AC97Constants.SAMPLE_RATE_8000;

    /**
     * Highest rate the converter handles.
     */
    public static final int MAX_RATE = 96000;

    /**
     * Zero level of the 16-bit sample range.
     */
    private static final int SAMPLE_ZERO = 0;

    /**
     * Highest positive sample value.
     */
    private static final int SAMPLE_MAX = 32767;

    /**
     * Lowest negative sample value.
     */
    private static final int SAMPLE_MIN = -32768;

    private final int fromRate;
    private final int toRate;

    /**
     * Input frames consumed per output frame.
     */
    private final double step;

    /**
     * Position of the next output frame in input frames, relative to the
     * start of the next chunk. It is always in [0, step), so it is never
     * before the chunk.
     */
    private double pos;

    /**
     * Creates a converter.
     *
     * @param fromRate the sample rate of the input
     * @param toRate   the sample rate of the output
     */
    public SampleRateConverter(int fromRate, int toRate) {
        if ((fromRate < MIN_RATE) || (fromRate > MAX_RATE)) {
            throw new IllegalArgumentException("Unsupported source rate "
                + fromRate);
        }
        if ((toRate < MIN_RATE) || (toRate > MAX_RATE)) {
            throw new IllegalArgumentException("Unsupported target rate "
                + toRate);
        }
        this.fromRate = fromRate;
        this.toRate = toRate;
        this.step = (double) fromRate / (double) toRate;
        this.pos = 0.0;
    }

    public int getFromRate() {
        return fromRate;
    }

    public int getToRate() {
        return toRate;
    }

    /**
     * Gets the number of output frames the given number of input frames
     * produces, ignoring the sub-frame remainder.
     */
    public int outputFramesFor(int inputFrames) {
        return (int) (((double) inputFrames * toRate) / fromRate);
    }

    /**
     * Converts one chunk of PCM data.
     *
     * @param src         source PCM data, 16 bit signed stereo
     * @param srcOffset   offset in the source array
     * @param srcFrames   number of stereo frames in the source
     * @param dst         destination array
     * @param dstOffset   offset in the destination array
     * @return the number of bytes written to the destination
     */
    public int convert(byte[] src, int srcOffset, int srcFrames, byte[] dst,
        int dstOffset) {
        if (srcFrames <= 0) {
            return 0;
        }
        final int maxOutFrames = (dst.length - dstOffset)
            / AC97Constants.PCM_FRAME_SIZE;
        int outFrames = 0;
        while (outFrames < maxOutFrames) {
            final double p = pos + (outFrames * step);
            if (p >= srcFrames) {
                break;
            }
            final int i0 = (int) p;
            final double frac = p - i0;
            final int ofs = srcOffset + i0 * AC97Constants.PCM_FRAME_SIZE;
            final int l0 = sampleAt(src, ofs);
            final int r0 = sampleAt(src, ofs + 2);
            final int l1;
            final int r1;
            if ((frac > 0.0) && ((i0 + 1) < srcFrames)) {
                l1 = sampleAt(src, ofs + AC97Constants.PCM_FRAME_SIZE);
                r1 = sampleAt(src, ofs + AC97Constants.PCM_FRAME_SIZE + 2);
            } else {
                // At the end of the chunk there is no frame after this one;
                // interpolation then degrades to holding the sample.
                l1 = l0;
                r1 = r0;
            }
            final int left = clamp(l0 + (int) ((l1 - l0) * frac));
            final int right = clamp(r0 + (int) ((r1 - r0) * frac));
            final int dstOfs = dstOffset + outFrames
                * AC97Constants.PCM_FRAME_SIZE;
            dst[dstOfs] = (byte) left;
            dst[dstOfs + 1] = (byte) (left >> 8);
            dst[dstOfs + 2] = (byte) right;
            dst[dstOfs + 3] = (byte) (right >> 8);
            outFrames++;
        }
        // The position of the next output frame, relative to the start of the
        // next chunk. It stays below step because the loop only stops once
        // the next output position has passed the end of this chunk.
        pos = pos + (outFrames * step) - srcFrames;
        if (pos < 0.0) {
            pos = 0.0;
        }
        return outFrames * AC97Constants.PCM_FRAME_SIZE;
    }

    /**
     * Gets the fractional position of the next output frame, in input
     * frames, relative to the start of the next chunk. Exposed for tests.
     */
    double getPosition() {
        return pos;
    }

    private static int sampleAt(byte[] src, int offset) {
        return (src[offset] & 0xFF) | (src[offset + 1] << 8);
    }

    private static int clamp(int sample) {
        if (sample > SAMPLE_MAX) {
            return SAMPLE_MAX;
        }
        if (sample < SAMPLE_MIN) {
            return SAMPLE_MIN;
        }
        return SAMPLE_ZERO + sample;
    }
}
