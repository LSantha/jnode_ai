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
 * The sample format of an AC'97 PCM stream: the number of channels and the
 * number of bits per sample.
 * <p>
 * Every AC'97 controller plays 16 bit stereo, which is the default. Some
 * controllers and codecs also play 20 bit samples, which the controller then
 * stores in the upper part of 32 bit words, and 4 or 6 channel output. The
 * frame size, which is what the DMA engine counts in its descriptors, follows
 * from the format.
 * <p>
 * This class is pure arithmetic and is unit tested; the hardware side lives in
 * {@link AC97Core#setSpeakerFormat}.
 *
 * @author JNode contributors
 */
public final class PcmFormat {

    /**
     * The default format: 16 bit stereo, what every codec can play.
     */
    public static final PcmFormat STEREO_16 = new PcmFormat(2, 16);

    /**
     * 20 bit stereo, where a sample is stored in the upper bits of a 32 bit
     * word.
     */
    public static final PcmFormat STEREO_20 = new PcmFormat(2, 20);

    private final int channels;
    private final int bitsPerSample;

    /**
     * Creates a format.
     *
     * @param channels      2, 4 or 6 output channels
     * @param bitsPerSample 16 or 20 bits per sample
     */
    public PcmFormat(int channels, int bitsPerSample) {
        if ((channels != 2) && (channels != 4) && (channels != 6)) {
            throw new IllegalArgumentException("Unsupported channel count "
                + channels);
        }
        if ((bitsPerSample != 16) && (bitsPerSample != 20)) {
            throw new IllegalArgumentException(
                "Unsupported sample size " + bitsPerSample + " bits");
        }
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Gets the number of bytes one sample occupies: 16 bit samples are two
     * bytes and 20 bit samples are stored in 32 bit words.
     */
    public int getSampleBytes() {
        return (bitsPerSample == 20) ? 4 : 2;
    }

    /**
     * Gets the size of one sample frame in bytes, which is what the DMA
     * descriptors count.
     */
    public int getFrameSize() {
        return channels * getSampleBytes();
    }

    /**
     * Gets the number of 16 bit words that make up one sample frame, which is
     * what the buffer descriptor length field counts, whatever the format.
     */
    public int getWordsPerFrame() {
        // The descriptors count 16 bit words, and a 20 bit sample occupies a
        // whole 32 bit word, so the count is the frame size in words rather
        // than the bit count divided by sixteen.
        return (channels * getSampleBytes()) / 2;
    }

    /**
     * Gets the number of 16 bit words in a buffer of the given number of
     * bytes.
     */
    public int wordsIn(int bytes) {
        if ((bytes % getFrameSize()) != 0) {
            throw new IllegalArgumentException("Length is not a whole number"
                + " of frames: " + bytes);
        }
        return (bytes / getFrameSize()) * getWordsPerFrame();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof PcmFormat)) {
            return false;
        }
        final PcmFormat other = (PcmFormat) obj;
        return (channels == other.channels)
            && (bitsPerSample == other.bitsPerSample);
    }

    @Override
    public int hashCode() {
        return (channels << 8) | bitsPerSample;
    }

    @Override
    public String toString() {
        return bitsPerSample + "bit " + channels + "ch";
    }
}
