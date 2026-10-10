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
 * Reading and writing of RIFF/WAVE files in the uncompressed PCM formats the
 * AC'97 DAC understands: 8 and 16 bit samples, mono and stereo.
 * <p>
 * The class is pure byte arithmetic so that it can be unit tested without a
 * filesystem or a codec. Only the parts a simple player and recorder need are
 * implemented; anything else is rejected with a clear message instead of
 * being played as noise.
 *
 * @author JNode contributors
 */
public final class WavFile {

    /**
     * Size of the canonical RIFF/WAVE header: the 12 byte RIFF chunk, the 24
     * byte format chunk and the 8 byte data chunk header.
     */
    public static final int HEADER_SIZE = 44;

    /**
     * Maximum size of a header that is parsed; files with more metadata than
     * this have their header skipped by looking for the data chunk.
     */
    public static final int MAX_HEADER_SIZE = 4096;

    private static final int CHUNK_HEADER_SIZE = 8;

    private static final int FORMAT_TAG_PCM = 1;

    /**
     * Utility class.
     */
    private WavFile() {
    }

    /**
     * The interesting parts of a WAVE file.
     */
    public static final class Format {
        private final int channels;
        private final int sampleRate;
        private final int bitsPerSample;
        private final int dataOffset;
        private final int dataLength;

        Format(int channels, int sampleRate, int bitsPerSample, int dataOffset,
            int dataLength) {
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }

        public int getChannels() {
            return channels;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public int getBitsPerSample() {
            return bitsPerSample;
        }

        /**
         * Offset of the first PCM byte in the file.
         */
        public int getDataOffset() {
            return dataOffset;
        }

        /**
         * Number of PCM bytes in the file.
         */
        public int getDataLength() {
            return dataLength;
        }

        /**
         * Size of one sample frame in bytes: one sample per channel.
         */
        public int getFrameSize() {
            return channels * (bitsPerSample / 8);
        }

        /**
         * Number of sample frames described by the data chunk.
         */
        public int getFrameCount() {
            return dataLength / getFrameSize();
        }

        @Override
        public String toString() {
            return "PCM " + bitsPerSample + "bit " + channels
                + (channels == 1 ? "channel" : "channels") + " " + sampleRate
                + "Hz " + dataLength + " bytes";
        }
    }

    /**
     * Creates the canonical 44 byte RIFF/WAVE header for PCM data.
     *
     * @param dataLength    number of PCM bytes that follow
     * @param sampleRate    sample rate in Hz
     * @param channels      1 for mono, 2 for stereo
     * @param bitsPerSample 8 or 16
     */
    public static byte[] createHeader(int dataLength, int sampleRate,
        int channels, int bitsPerSample) {
        final int byteRate = sampleRate * channels * (bitsPerSample / 8);
        final int blockAlign = channels * (bitsPerSample / 8);
        final byte[] header = new byte[HEADER_SIZE];
        // "RIFF"
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        putIntLE(header, 4, HEADER_SIZE - 8 + dataLength);
        // "WAVE"
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        // "fmt " chunk, 16 bytes, PCM
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        putIntLE(header, 16, 16);
        putShortLE(header, 20, FORMAT_TAG_PCM);
        putShortLE(header, 22, channels);
        putIntLE(header, 24, sampleRate);
        putIntLE(header, 28, byteRate);
        putShortLE(header, 32, blockAlign);
        putShortLE(header, 34, bitsPerSample);
        // "data" chunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        putIntLE(header, 40, dataLength);
        return header;
    }

    /**
     * Parses the header of a RIFF/WAVE file.
     *
     * @param header  the first bytes of the file, at least
     *                {@link #HEADER_SIZE} of them
     * @param length  the number of valid bytes in the array
     * @return the parsed format
     * @throws IllegalArgumentException if the file is not a PCM WAVE file this
     *                                  class can play or record
     */
    public static Format parseFormat(byte[] header, int length) {
        if (length < HEADER_SIZE) {
            throw new IllegalArgumentException("WAV header too short: " + length);
        }
        if (!isTag(header, 0, 'R', 'I', 'F', 'F')) {
            throw new IllegalArgumentException("Not a RIFF file");
        }
        if (!isTag(header, 8, 'W', 'A', 'V', 'E')) {
            throw new IllegalArgumentException("Not a WAVE file");
        }
        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataLength = -1;
        while (offset + CHUNK_HEADER_SIZE <= length) {
            final int chunkLength = getIntLE(header, offset + 4);
            final int chunkData = offset + CHUNK_HEADER_SIZE;
            if (chunkData + chunkLength > length) {
                // The data chunk may be the last one and be truncated only by
                // the size of the read buffer, which is fine for the format
                // chunk but not for a format chunk itself.
                if (!isTag(header, offset, 'd', 'a', 't', 'a')) {
                    break;
                }
            }
            if (isTag(header, offset, 'f', 'm', 't', ' ')) {
                if (chunkLength < 16) {
                    throw new IllegalArgumentException("Format chunk too short");
                }
                final int tag = getShortLE(header, chunkData);
                if (tag != FORMAT_TAG_PCM) {
                    throw new IllegalArgumentException(
                        "Not uncompressed PCM (format tag " + tag + ')');
                }
                channels = getShortLE(header, chunkData + 2);
                sampleRate = getIntLE(header, chunkData + 4);
                bitsPerSample = getShortLE(header, chunkData + 14);
            } else if (isTag(header, offset, 'd', 'a', 't', 'a')) {
                dataOffset = chunkData;
                dataLength = chunkLength;
                // The data chunk is the last one that matters; the rest is
                // metadata.
                break;
            }
            // Chunks are word aligned, so an odd length is padded.
            offset = chunkData + chunkLength + (chunkLength & 1);
        }
        if (dataOffset < 0) {
            throw new IllegalArgumentException("No data chunk found");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Unsupported channel count "
                + channels);
        }
        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new IllegalArgumentException("Unsupported sample size "
                + bitsPerSample + " bit");
        }
        if (sampleRate < 4000 || sampleRate > 48000) {
            throw new IllegalArgumentException("Unsupported sample rate "
                + sampleRate);
        }
        return new Format(channels, sampleRate, bitsPerSample, dataOffset,
            dataLength);
    }

    /**
     * Converts PCM data to the format the AC'97 driver plays: 16 bit signed
     * little endian samples, stereo. 8 bit samples are unsigned with a zero
     * level of 128, and mono samples are duplicated into both channels.
     * <p>
     * The number of source bytes must be a whole number of sample frames,
     * which the caller guarantees by reading in frame sized chunks.
     *
     * @param src           source PCM data
     * @param srcOffset     offset in the source array
     * @param length        number of source bytes
     * @param dst           destination array
     * @param dstOffset     offset in the destination array
     * @param channels      1 or 2 channels in the source
     * @param bitsPerSample 8 or 16 bits per sample in the source
     * @return the number of bytes written to the destination
     */
    public static int convertToStereo16(byte[] src, int srcOffset, int length,
        byte[] dst, int dstOffset, int channels, int bitsPerSample) {
        final int frameSize = channels * (bitsPerSample / 8);
        if (frameSize <= 0) {
            throw new IllegalArgumentException("Invalid format " + channels
                + " channels " + bitsPerSample + " bits");
        }
        final int frames = length / frameSize;
        int dstIndex = dstOffset;
        for (int i = 0; i < frames; i++) {
            final int frameOffset = srcOffset + i * frameSize;
            int left;
            int right;
            if (bitsPerSample == 16) {
                left = readSample16(src, frameOffset);
                right = channels == 2 ? readSample16(src, frameOffset + 2) : left;
            } else {
                left = (src[frameOffset] - 128) << 8;
                right = channels == 2 ? (src[frameOffset + 1] - 128) << 8 : left;
            }
            dst[dstIndex] = (byte) left;
            dst[dstIndex + 1] = (byte) (left >> 8);
            dst[dstIndex + 2] = (byte) right;
            dst[dstIndex + 3] = (byte) (right >> 8);
            dstIndex += 4;
        }
        return dstIndex - dstOffset;
    }

    /**
     * Gets the number of bytes one call to
     * {@link #convertToStereo16} writes for the given source length.
     */
    public static int convertedLength(int length, int channels, int bitsPerSample) {
        final int frameSize = channels * (bitsPerSample / 8);
        if (frameSize <= 0) {
            throw new IllegalArgumentException("Invalid format " + channels
                + " channels " + bitsPerSample + " bits");
        }
        return (length / frameSize) * 4;
    }

    private static int readSample16(byte[] src, int offset) {
        return (src[offset] & 0xFF) | (src[offset + 1] << 8);
    }

    private static boolean isTag(byte[] buffer, int offset, char c1, char c2,
        char c3, char c4) {
        return buffer[offset] == c1 && buffer[offset + 1] == c2
            && buffer[offset + 2] == c3 && buffer[offset + 3] == c4;
    }

    private static int getShortLE(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | (buffer[offset + 1] << 8);
    }

    private static int getIntLE(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
            | ((buffer[offset + 2] & 0xFF) << 16) | (buffer[offset + 3] << 24);
    }

    private static void putShortLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
    }

    private static void putIntLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
        buffer[offset + 2] = (byte) (value >> 16);
        buffer[offset + 3] = (byte) (value >> 24);
    }
}
