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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Host-runnable test for the RIFF/WAVE support of {@link WavFile}.
 *
 * @author JNode contributors
 */
public class WavFileTest {

    private static final int FORMAT_TAG_PCM = 1;

    @Test
    public void testCreatedHeader() {
        final byte[] header = WavFile.createHeader(32000, 44100, 2, 16);
        assertEquals(WavFile.HEADER_SIZE, header.length);
        assertEquals('R', header[0]);
        assertEquals('I', header[1]);
        assertEquals('F', header[2]);
        assertEquals('F', header[3]);
        assertEquals('W', header[8]);
        assertEquals('A', header[9]);
        assertEquals('V', header[10]);
        assertEquals('E', header[11]);
        assertEquals('f', header[12]);
        assertEquals('m', header[13]);
        assertEquals('t', header[14]);
        assertEquals(' ', header[15]);
        assertEquals('d', header[36]);
        assertEquals('a', header[37]);
        assertEquals('t', header[38]);
        assertEquals('a', header[39]);
        // RIFF size covers everything after the size field itself.
        assertEquals(WavFile.HEADER_SIZE - 8 + 32000, getInt(header, 4));
        // The format chunk is 16 bytes of PCM data.
        assertEquals(16, getInt(header, 16));
        assertEquals(FORMAT_TAG_PCM, getShort(header, 20));
        assertEquals(2, getShort(header, 22));
        assertEquals(44100, getInt(header, 24));
        // Byte rate and block alignment are derived from the parameters.
        assertEquals(44100 * 2 * 2, getInt(header, 28));
        assertEquals(4, getShort(header, 32));
        assertEquals(16, getShort(header, 34));
        assertEquals(32000, getInt(header, 40));
    }

    @Test
    public void testCreatedHeaderRoundTrips() {
        final byte[] header = WavFile.createHeader(12345, 22050, 1, 8);
        final WavFile.Format format = WavFile.parseFormat(header,
            header.length);
        assertEquals(1, format.getChannels());
        assertEquals(22050, format.getSampleRate());
        assertEquals(8, format.getBitsPerSample());
        assertEquals(WavFile.HEADER_SIZE, format.getDataOffset());
        assertEquals(12345, format.getDataLength());
        assertEquals(12345, format.getFrameCount());
        assertEquals(1, format.getFrameSize());
    }

    @Test
    public void testParseSkipsMetadataChunks() {
        // A file with a LIST chunk and an extra fmt field between the format
        // and the data chunk still parses: the data chunk is found by
        // walking the chunks.
        final byte[] header = WavFile.createHeader(1000, 48000, 2, 16);
        final byte[] extended = new byte[header.length + 32];
        System.arraycopy(header, 0, extended, 0, header.length);
        // Append a LIST chunk after the data chunk; the data chunk must still
        // be found first, and the parse stops there.
        int offset = header.length;
        extended[offset++] = 'L';
        extended[offset++] = 'I';
        extended[offset++] = 'S';
        extended[offset++] = 'T';
        putInt(extended, offset, 24);
        final WavFile.Format format = WavFile.parseFormat(extended,
            extended.length);
        assertEquals(2, format.getChannels());
        assertEquals(48000, format.getSampleRate());
        assertEquals(WavFile.HEADER_SIZE, format.getDataOffset());
        assertEquals(1000, format.getDataLength());
    }

    @Test
    public void testParseOddChunkPadding() {
        // Chunks are word aligned: an odd sized chunk is padded by one byte,
        // and the next chunk starts after the padding.
        final byte[] header = new byte[WavFile.HEADER_SIZE + 20];
        System.arraycopy(WavFile.createHeader(512, 16000, 2, 16), 0, header, 0,
            WavFile.HEADER_SIZE);
        final int offset = WavFile.HEADER_SIZE;
        header[offset] = 'f';
        header[offset + 1] = 'a';
        header[offset + 2] = 'k';
        header[offset + 3] = 'e';
        putInt(header, offset + 4, 3); // odd size
        header[offset + 12] = 'd';
        header[offset + 13] = 'a';
        header[offset + 14] = 't';
        header[offset + 15] = 'a';
        putInt(header, offset + 16, 256);
        final WavFile.Format format = WavFile.parseFormat(header, header.length);
        // The data chunk of the file, not the padded fake one.
        assertEquals(512, format.getDataLength());
        assertEquals(16000, format.getSampleRate());
    }

    @Test
    public void testParseRejectsBadFiles() {
        final byte[] notRiff = new byte[WavFile.HEADER_SIZE];
        notRiff[0] = 'X';
        try {
            WavFile.parseFormat(notRiff, notRiff.length);
            fail("expected an exception for a non RIFF file");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        final byte[] notWave = new byte[WavFile.HEADER_SIZE];
        notWave[0] = 'R';
        notWave[1] = 'I';
        notWave[2] = 'F';
        notWave[3] = 'F';
        notWave[8] = 'A';
        notWave[9] = 'V';
        notWave[10] = 'I';
        notWave[11] = ' ';
        try {
            WavFile.parseFormat(notWave, notWave.length);
            fail("expected an exception for a non WAVE file");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // Too short to be a header at all.
        try {
            WavFile.parseFormat(new byte[20], 20);
            fail("expected an exception for a short header");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testParseRejectsUnsupportedFormats() {
        // A compressed format tag is not PCM.
        byte[] header = WavFile.createHeader(100, 44100, 2, 16);
        putShort(header, 20, 3);
        try {
            WavFile.parseFormat(header, header.length);
            fail("expected an exception for a non PCM file");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // More than two channels.
        header = WavFile.createHeader(100, 44100, 2, 16);
        putShort(header, 22, 6);
        try {
            WavFile.parseFormat(header, header.length);
            fail("expected an exception for 6 channels");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // 24 bit samples.
        header = WavFile.createHeader(100, 44100, 2, 16);
        putShort(header, 34, 24);
        try {
            WavFile.parseFormat(header, header.length);
            fail("expected an exception for 24 bit samples");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // A rate the codec cannot reach.
        header = WavFile.createHeader(100, 96000, 2, 16);
        try {
            WavFile.parseFormat(header, header.length);
            fail("expected an exception for a 96 kHz file");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // Zero channels.
        header = WavFile.createHeader(100, 44100, 2, 16);
        putShort(header, 22, 0);
        try {
            WavFile.parseFormat(header, header.length);
            fail("expected an exception for 0 channels");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testParseFindsTheDataChunkAfterMetadata() {
        // A WAV with a LIST metadata chunk after the format chunk must still
        // be parsed: the data offset is past the metadata, and the samples
        // after the data chunk header are the ones to convert. The parser
        // used to stop at the canonical data offset of 44 bytes, which is the
        // middle of the metadata in such a file.
        final byte[] canonical = WavFile.createHeader(24, 48000, 2, 16);
        // The format chunk occupies 12..36, so a metadata chunk starts at the
        // offset the canonical data chunk header would use.
        final int metaOffset = 36;      // where the canonical data header sits
        final int dataHeader = metaOffset + 16;       // behind the padded metadata chunk
        final int dataOffset = dataHeader + 8;        // payload, behind the data header
        final byte[] extended = new byte[dataOffset + 24];
        // "RIFF....WAVE"
        System.arraycopy(canonical, 0, extended, 0, 12);
        // "fmt " chunk header plus the 16 payload bytes
        System.arraycopy(canonical, 12, extended, 12, 24);
        // "LIST", 8 byte payload, padded to 16
        extended[metaOffset] = 'L';
        extended[metaOffset + 1] = 'I';
        extended[metaOffset + 2] = 'S';
        extended[metaOffset + 3] = 'T';
        putInt(extended, metaOffset + 4, 8);
        extended[metaOffset + 8] = 'I';
        extended[metaOffset + 9] = 'N';
        extended[metaOffset + 10] = 'F';
        extended[metaOffset + 11] = 'O';
        // "data" chunk header plus the 24 payload bytes
        extended[dataHeader] = 'd';
        extended[dataHeader + 1] = 'a';
        extended[dataHeader + 2] = 't';
        extended[dataHeader + 3] = 'a';
        putInt(extended, dataHeader + 4, 24);
        for (int i = 0; i < 24; i++) {
            extended[dataOffset + i] = (byte) (i * 7);
        }
        final WavFile.Format format = WavFile.parseFormat(extended,
            extended.length);
        assertEquals(2, format.getChannels());
        assertEquals(48000, format.getSampleRate());
        assertEquals(24, format.getDataLength());
        assertEquals("the data chunk must be found after the metadata",
            dataOffset, format.getDataOffset());
        // The payload really starts where the format says, and it converts.
        assertEquals(0, extended[dataOffset] & 0xFF);
        assertEquals(7, extended[dataOffset + 1] & 0xFF);
        final byte[] dst = new byte[24];
        assertEquals(24, WavFile.convertToStereo16(extended, dataOffset,
            24, dst, 0, 2, 16));
    }

    @Test
    public void testConversionIsIdentityForStereoSixteen() {
        final byte[] src = {0x34, 0x12, 0x78, 0x56, (byte) 0xCD, (byte) 0xAB,
            0x00, 0x00};
        final byte[] dst = new byte[src.length];
        final int written = WavFile.convertToStereo16(src, 0, src.length, dst,
            0, 2, 16);
        assertEquals(src.length, written);
        assertArrayEquals(src, dst);
    }

    @Test
    public void testConversionDuplicatesMono() {
        // A mono frame becomes a stereo frame with the same sample twice.
        final byte[] src = {0x00, 0x40}; // 0x4000
        final byte[] dst = new byte[8];
        final int written = WavFile.convertToStereo16(src, 0, 2, dst, 0, 1, 16);
        assertEquals(4, written);
        assertEquals(0x00, dst[0] & 0xFF);
        assertEquals(0x40, dst[1] & 0xFF);
        assertEquals(0x00, dst[2] & 0xFF);
        assertEquals(0x40, dst[3] & 0xFF);
    }

    @Test
    public void testConversionOfUnsignedEightBit() {
        // 8 bit PCM is unsigned around a zero level of 128. A stereo frame is
        // two source bytes and becomes four output bytes, so this is the
        // conversion of four frames: negative extreme, positive extreme,
        // silence and half level negative.
        final byte[] src = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0x80,
            (byte) 0x80, 0x40, 0x40};
        final byte[] dst = new byte[16];
        final int written = WavFile.convertToStereo16(src, 0, src.length, dst,
            0, 2, 8);
        assertEquals(16, written);
        // 0x00 is the negative extreme: -128 << 8 = 0x8000.
        assertFrame(dst, 0, 0x8000, 0x8000);
        // 0xFF is the positive extreme: +127 << 8 = 0x7F00.
        assertFrame(dst, 4, 0x7F00, 0x7F00);
        // 0x80 is the zero level.
        assertFrame(dst, 8, 0, 0);
        // 0x40 is negative: -64 << 8 = 0xC000.
        assertFrame(dst, 12, 0xC000, 0xC000);
    }

    @Test
    public void testConversionOfUnsignedEightBitMono() {
        // A mono 8 bit frame is one source byte and becomes a stereo frame
        // with the same sample on both channels.
        final byte[] src = {0x00, (byte) 0x80, (byte) 0xFF};
        final byte[] dst = new byte[12];
        final int written = WavFile.convertToStereo16(src, 0, src.length, dst,
            0, 1, 8);
        assertEquals(12, written);
        assertFrame(dst, 0, 0x8000, 0x8000);
        assertFrame(dst, 4, 0, 0);
        assertFrame(dst, 8, 0x7F00, 0x7F00);
    }

    /**
     * Checks one converted stereo frame: little endian samples at the given
     * offset in the destination buffer.
     */
    private void assertFrame(byte[] dst, int offset, int left, int right) {
        assertEquals("left low byte", left & 0xFF, dst[offset] & 0xFF);
        assertEquals("left high byte", (left >> 8) & 0xFF, dst[offset + 1] & 0xFF);
        assertEquals("right low byte", right & 0xFF, dst[offset + 2] & 0xFF);
        assertEquals("right high byte", (right >> 8) & 0xFF, dst[offset + 3] & 0xFF);
    }

    @Test
    public void testConversionDropsIncompleteTrailingFrames() {
        // A read that ends in the middle of a frame has the rest of the frame
        // ignored rather than played as a mangled sample.
        final byte[] src = {0x01, 0x02, 0x03, 0x04, 0x05};
        final byte[] dst = new byte[16];
        final int written = WavFile.convertToStereo16(src, 0, src.length, dst,
            0, 2, 16);
        assertEquals(4, written);
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0}, dst);
    }

    @Test
    public void testConvertedLength() {
        // One output frame is always 4 bytes, so the count is the number of
        // source frames in the given length.
        assertEquals(8, WavFile.convertedLength(8, 2, 16));   // 2 frames
        assertEquals(32, WavFile.convertedLength(8, 1, 8));    // 8 frames
        assertEquals(16, WavFile.convertedLength(8, 1, 16));   // 4 frames
        assertEquals(16, WavFile.convertedLength(8, 2, 8));    // 4 frames
        // An incomplete trailing frame is not counted.
        assertEquals(12, WavFile.convertedLength(6, 1, 16));   // 2 of 3 frames
        // Nonsense sample sizes are rejected instead of dividing by zero.
        try {
            WavFile.convertedLength(8, 1, 2);
            fail("expected an exception for a 2 bit sample size");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testLengthsMatchTheConversion() {
        // The helper must predict exactly what the conversion writes, or a
        // streaming player would leave garbage in its output buffer.
        final byte[] src = new byte[4096];
        final byte[] dst = new byte[16384];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        for (int channels : new int[] {1, 2}) {
            for (int bits : new int[] {8, 16}) {
                final int predicted = WavFile.convertedLength(src.length,
                    channels, bits);
                final int written = WavFile.convertToStereo16(src, 0,
                    src.length, dst, 0, channels, bits);
                assertEquals("channels=" + channels + " bits=" + bits,
                    predicted, written);
                assertTrue(predicted <= dst.length);
            }
        }
    }

    private static int getShort(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | (buffer[offset + 1] << 8);
    }

    private static int getInt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
            | ((buffer[offset + 2] & 0xFF) << 16) | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static void putShort(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
    }

    private static void putInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
        buffer[offset + 2] = (byte) (value >> 16);
        buffer[offset + 3] = (byte) (value >> 24);
    }
}
