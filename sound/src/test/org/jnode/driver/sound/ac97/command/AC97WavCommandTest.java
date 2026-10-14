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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.jnode.driver.sound.ac97.AC97Constants;
import org.jnode.driver.sound.ac97.WavFile;
import org.jnode.driver.sound.ac97.command.AC97WavCommand.Header;
import org.junit.Test;

/**
 * Host-runnable test for the duration a played file is reported as.
 * <p>
 * A converted file is resampled, so the frames written to the codec are frames
 * of the playback rate and not frames of the rate the file was recorded at.
 * The duration has to be computed from the rate the codec played the frames
 * at, otherwise a converted file reads as longer than it sounds.
 *
 * @author JNode contributors
 */
public class AC97WavCommandTest {

    @Test
    public void testDurationOfAConvertedFileIsPlaybackTime() {
        // Half a second of 22050Hz audio, played at 48000Hz after resampling:
        // the frames written are frames of the playback rate.
        final int fileRate = 22050;
        final int playRate = AC97Constants.SAMPLE_RATE_48000;
        final int sourceFrames = fileRate / 2;
        final int playedFrames = sourceFrames * playRate / fileRate;
        assertEquals("half a second of audio plays for half a second", 500,
            AC97WavCommand.playedMillis(playedFrames, playRate));
        assertTrue("the source rate must not be used for the duration",
            500 != AC97WavCommand.playedMillis(playedFrames, fileRate));
    }

    @Test
    public void testDurationOfAFilePlayedAtItsOwnRate() {
        // A file the codec can play without resampling reports the same
        // duration either way, which is the common case.
        final int rate = AC97Constants.SAMPLE_RATE_48000;
        assertEquals(1000, AC97WavCommand.playedMillis(rate, rate));
        assertEquals(250, AC97WavCommand.playedMillis(rate / 4, rate));
    }

    @Test
    public void testDurationOfAnUnplayedFile() {
        assertEquals(0, AC97WavCommand.playedMillis(0, 48000));
    }

    /**
     * Builds a WAV file whose data chunk starts behind a metadata chunk, so
     * the format does not parse inside the canonical 44 header bytes. Tagged
     * editors and some recorders write files like this.
     */
    private byte[] extendedHeaderWav(int pcmBytes) {
        // A rate the codec does not play natively, so the file has to be
        // resampled to be played.
        final int rate = 24000;
        final int dataBytes = pcmBytes;
        // "RIFF", the 24 byte format chunk, a 16 byte metadata chunk in place
        // of the canonical data chunk header, then the data chunk payload.
        final int metaOffset = 36;
        final int dataChunk = metaOffset + 16;
        final int dataOffset = dataChunk + 8;
        final byte[] wav = new byte[dataOffset + dataBytes];
        System.arraycopy(WavFile.createHeader(dataBytes, rate, 2, 16), 0, wav,
            0, WavFile.HEADER_SIZE);
        // A 16 byte metadata chunk: "LIST", 8 bytes of payload, padded.
        wav[metaOffset] = 'L';
        wav[metaOffset + 1] = 'I';
        wav[metaOffset + 2] = 'S';
        wav[metaOffset + 3] = 'T';
        putIntLE(wav, metaOffset + 4, 8);
        wav[metaOffset + 8] = 'I';
        wav[metaOffset + 9] = 'N';
        wav[metaOffset + 10] = 'F';
        wav[metaOffset + 11] = 'O';
        // The data chunk header, and payload behind it.
        wav[dataChunk] = 'd';
        wav[dataChunk + 1] = 'a';
        wav[dataChunk + 2] = 't';
        wav[dataChunk + 3] = 'a';
        putIntLE(wav, dataChunk + 4, dataBytes);
        for (int i = 0; i < dataBytes; i++) {
            wav[dataOffset + i] = (byte) (i * 5);
        }
        // A trailing chunk behind the data must not disturb the parse.
        return wav;
    }

    private void putIntLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
        buffer[offset + 2] = (byte) (value >> 16);
        buffer[offset + 3] = (byte) (value >> 24);
    }

    /**
     * Reads a stream in the small pieces the file system gives, so that the
     * retry has to deal with a stream that is not block aligned.
     */
    private InputStream dripping(final byte[] data) {
        return new InputStream() {
            private int position;

            public int read() {
                return position < data.length ? data[position++] & 0xFF : -1;
            }

            public int read(byte[] b, int off, int len) {
                if (position >= data.length) {
                    return -1;
                }
                final int n = Math.min(Math.min(len, 3), data.length - position);
                System.arraycopy(data, position, b, off, n);
                position += n;
                return n;
            }
        };
    }

    @Test
    public void testHeaderWithMetadataBeatsTheCanonicalFortyFourBytes()
        throws IOException {
        // A file with a metadata chunk behind the format chunk does not parse
        // in 44 bytes, so the wider read has to find the data chunk behind
        // it. The stream delivers its bytes a few at a time, as a file system
        // is free to do.
        final byte[] wav = extendedHeaderWav(600);
        final int dataOffset = 36 + 16 + 8;
        assertTrue("the file must fit in one wide buffer", wav.length < WavFile.MAX_HEADER_SIZE);
        final Header header = AC97WavCommand.readHeader(dripping(wav));
        assertEquals(24000, header.getFormat().getSampleRate());
        assertEquals("the data chunk is behind the metadata chunk", dataOffset,
            header.getFormat().getDataOffset());
        // The whole file is shorter than the wide buffer, so every data byte
        // came with the header, and the stream has nothing left to give.
        assertEquals(wav.length - dataOffset, header.getBuffered());
    }

    @Test
    public void testHeaderKeepsTheBytesAlreadyRead() throws IOException {
        // The retry appends behind the bytes it already has; a retry that
        // reads into the start of the buffer would hide the RIFF header and
        // the file would be reported as not being a WAV file at all. The file
        // is longer than the wide buffer, so the header read stops at the end
        // of the buffer and the rest of the data comes from the stream.
        final byte[] wav = extendedHeaderWav(24000);
        final Header header = AC97WavCommand.readHeader(new ByteArrayInputStream(
            wav));
        for (int i = 0; i < WavFile.HEADER_SIZE; i++) {
            assertEquals("byte " + i + " must survive the retry", wav[i],
                header.getBytes()[i]);
        }
        assertEquals(wav[WavFile.HEADER_SIZE], header.getBytes()[WavFile.HEADER_SIZE]);
        assertEquals(wav[WavFile.HEADER_SIZE + 1],
            header.getBytes()[WavFile.HEADER_SIZE + 1]);
        // The file is longer than the wide buffer, so the whole buffer is
        // header plus data, and the bytes of the data chunk that were read
        // with it start behind the data chunk header.
        final int dataOffset = 36 + 16 + 8;
        assertEquals(WavFile.MAX_HEADER_SIZE - dataOffset, header.getBuffered());
        assertEquals(WavFile.MAX_HEADER_SIZE, header.getBytes().length);
    }

    @Test
    public void testCanonicalHeaderIsReadInOneGo() throws IOException {
        // The common case: 44 bytes and the data chunk is in them.
        final byte[] wav = WavFile.createHeader(4800, 48000, 2, 16);
        final byte[] pcm = new byte[4800];
        final byte[] file = new byte[wav.length + pcm.length];
        System.arraycopy(wav, 0, file, 0, wav.length);
        System.arraycopy(pcm, 0, file, wav.length, pcm.length);
        final Header header = AC97WavCommand.readHeader(new ByteArrayInputStream(
            file));
        // Only 44 bytes were read, so no PCM came with the header.
        assertEquals(0, header.getBuffered());
        assertEquals(48000, header.getFormat().getSampleRate());
    }

    @Test
    public void testShortFileIsStillAnError() throws IOException {
        final byte[] wav = WavFile.createHeader(4800, 48000, 2, 16);
        final byte[] cut = new byte[20];
        System.arraycopy(wav, 0, cut, 0, 20);
        try {
            AC97WavCommand.readHeader(new ByteArrayInputStream(cut));
            fail("expected an exception for a truncated header");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }
}
