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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import org.jnode.driver.sound.ac97.AC97API;
import org.jnode.driver.sound.ac97.AC97Constants;
import org.jnode.driver.sound.ac97.AC97Utils;
import org.jnode.driver.sound.ac97.SampleRateConverter;
import org.jnode.driver.sound.ac97.WavFile;
import org.jnode.shell.AbstractCommand;
import org.jnode.util.TimeoutException;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that plays a PCM WAV file through the AC'97 codec.
 * <p>
 * The header is parsed with {@link WavFile} and the samples are converted to
 * the 16 bit stereo format the DAC plays, so 8 bit and mono files work too.
 * Files that the codec cannot play are rejected with a message naming the
 * problem instead of being played as noise.
 *
 * @author JNode contributors
 */
public class AC97WavCommand extends AbstractCommand {

    /**
     * Extra room in the resample buffer, so that a chunk that ends on a frame
     * boundary cannot overflow it.
     */
    private static final int FRAME_PADDING = 16;

    private final FileArgument argFile = new FileArgument(
        "file", Argument.MANDATORY, "the WAV file to play");

    private final IntegerArgument argDevice = new IntegerArgument(
        "device", Argument.OPTIONAL, "index of the AC'97 device to use");

    public AC97WavCommand() {
        super("plays a PCM WAV file through the AC'97 codec");
        registerArguments(argFile, argDevice);
    }

    public static void main(String[] args) throws Exception {
        new AC97WavCommand().execute(args);
    }

    @Override
    public void execute() {
        final PrintWriter out = getOutput().getPrintWriter();
        final File file = argFile.getValue();
        final int deviceIndex = argDevice.isSet() ? argDevice.getValue()
            : 0;
        final AC97API api = AC97Utils.findDevice(deviceIndex);
        if (api == null) {
            out.println("ac97wav: " + AC97Utils.describeNoDevice(deviceIndex));
            exit(1);
            return;
        }

        final int chunkBytes = 8192;
        final byte[] raw = new byte[chunkBytes];
        int played = 0;
        InputStream in = null;
        boolean opened = false;
        try {
            in = new FileInputStream(file);
            // A canonical header is 44 bytes; a file with metadata chunks
            // behind the format chunk is read again with a wider buffer, and
            // the bytes already read are kept, so the second parse still sees
            // the header from the start of the file.
            final Header header = readHeader(in);
            final WavFile.Format format = header.getFormat();
            out.println("ac97wav: " + file.getPath() + " is " + format);
            // A file plays at its own rate if the codec can reach it, and is
            // resampled to 48000 Hz otherwise; the AC'97 DAC cannot play
            // anything but the specification rates.
            final int playRate = playbackRate(api, format.getSampleRate());
            final SampleRateConverter converter = (playRate == format
                .getSampleRate()) ? null : new SampleRateConverter(
                format.getSampleRate(), playRate);
            if (converter != null) {
                out.println("ac97wav: resampling " + format.getSampleRate()
                    + "Hz to " + playRate + "Hz");
            }
            api.open(playRate);
            opened = true;
            // The buffers hold one chunk worst case: 8 bit mono input is
            // multiplied by four per frame, and upsampling multiplies by the
            // rate ratio.
            final byte[] pcm = new byte[WavFile.convertedLength(chunkBytes, 1, 8)];
            final byte[] resampled = new byte[pcm.length * playRate
                / Math.max(1, format.getSampleRate()) + FRAME_PADDING];

            // PCM bytes that were read as part of the header buffer, either
            // because the header is longer than 44 bytes or because a whole
            // chunk was read, are played before the rest of the data chunk.
            long remaining = format.getDataLength();
            if (header.getBuffered() > 0) {
                final int buffered = header.getBuffered();
                played += playBuffer(api, format, header.getBytes(),
                    format.getDataOffset(), buffered, pcm, converter, resampled,
                    out);
                remaining -= buffered;
            }
            while (remaining > 0) {
                final int want = (int) Math.min(raw.length, remaining);
                final int read = readFully(in, raw, want);
                if (read <= 0) {
                    // A data chunk that promises more than the file holds is
                    // a broken file; play what is there and say so.
                    out.println("ac97wav: file is short by " + remaining
                        + " bytes");
                    break;
                }
                played += playBuffer(api, format, raw, 0, read, pcm,
                    converter, resampled, out);
                remaining -= read;
            }
            api.drain();
            // The duration of the played audio comes from the rate the codec
            // played it at: a converted file is resampled, so the frames that
            // were written are frames of the playback rate, not frames of the
            // source rate. Dividing by the source rate reports a file as
            // longer than it sounds.
            out.println("ac97wav: played " + played + " frames ("
                + playedMillis(played, playRate) + "ms)");
        } catch (IllegalArgumentException ex) {
            out.println("ac97wav: " + ex.getMessage());
            exit(1);
        } catch (IOException ex) {
            out.println("ac97wav: cannot read " + file.getPath() + ": "
                + ex.getMessage());
            exit(1);
        } catch (InterruptedException ex) {
            out.println("ac97wav: interrupted");
        } catch (Exception ex) {
            out.println("ac97wav: failed: " + ex);
            exit(1);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {
                    // ignored
                }
            }
            if (opened) {
                api.close();
            }
        }
    }

    /**
     * Converts one buffer of source PCM and writes it to the driver.
     *
     * @return the number of stereo frames written
     */
    private int playBuffer(AC97API api, WavFile.Format format, byte[] src,
        int srcOffset, int length, byte[] pcm, SampleRateConverter converter,
        byte[] resampled, PrintWriter out) throws InterruptedException,
        TimeoutException {
        final int len = WavFile.convertToStereo16(src, srcOffset, length, pcm,
            0, format.getChannels(), format.getBitsPerSample());
        if (len <= 0) {
            return 0;
        }
        int frames;
        byte[] buffer;
        if (converter == null) {
            frames = len / AC97Constants.PCM_FRAME_SIZE;
            buffer = pcm;
        } else {
            final int written = converter.convert(pcm, 0,
                len / AC97Constants.PCM_FRAME_SIZE, resampled, 0);
            if (written <= 0) {
                return 0;
            }
            frames = written / AC97Constants.PCM_FRAME_SIZE;
            buffer = resampled;
        }
        api.write(buffer, 0, frames * AC97Constants.PCM_FRAME_SIZE);
        return frames;
    }

    /**
     * Wall clock milliseconds that the given number of played frames take at
     * the given playback rate.
     *
     * @param frames frames written to the codec
     * @param rate the rate the codec plays them at
     * @return the duration in milliseconds
     */
    static int playedMillis(int frames, int rate) {
        return (int) (((long) frames * 1000) / rate);
    }

    /**
     * Gets the rate the codec will play at for a file of the given rate: the
     * file rate when the codec supports it, otherwise 48000 Hz, the only rate
     * every AC'97 codec has.
     */
    private int playbackRate(AC97API api, int fileRate) {
        if ((fileRate == AC97Constants.SAMPLE_RATE_48000)
            || (api.isVariableRateSupported() && isAc97Rate(fileRate))) {
            return fileRate;
        }
        return AC97Constants.SAMPLE_RATE_48000;
    }

    /**
     * Is the given rate one the AC'97 DAC can play without resampling?
     */
    private boolean isAc97Rate(int rate) {
        for (int r : AC97Constants.SAMPLE_RATES) {
            if (r == rate) {
                return true;
            }
        }
        return false;
    }

    /**
     * The header of a WAV file as it was read from the file: the bytes, how
     * many of them belong to the header, and the parsed format.
     */
    static final class Header {

        private final byte[] bytes;

        private final int length;

        private final WavFile.Format format;

        /**
         * @param bytes the bytes that were read for the header
         * @param length how many of them the file holds
         * @param format the format parsed from them
         */
        Header(byte[] bytes, int length, WavFile.Format format) {
            this.bytes = bytes;
            this.length = length;
            this.format = format;
        }

        byte[] getBytes() {
            return bytes;
        }

        WavFile.Format getFormat() {
            return format;
        }

        /**
         * PCM bytes of the data chunk that were read while reading the header,
         * which happens when the header is longer than the canonical 44 bytes.
         *
         * @return the number of bytes to play from {@link #getBytes()}
         */
        int getBuffered() {
            long buffered = length - format.getDataOffset();
            if (buffered > format.getDataLength()) {
                buffered = format.getDataLength();
            }
            return (int) buffered;
        }
    }

    /**
     * Reads the header of a WAV file and parses its format.
     * <p>
     * A canonical header is 44 bytes. A file with metadata chunks behind the
     * format chunk does not parse inside those bytes, so the header is read
     * again into a wider buffer that starts with the bytes already read:
     * appending behind them keeps the RIFF header at the start of the parse
     * instead of hiding it behind byte 44.
     *
     * @param in the WAV file, positioned at its first byte
     * @return the header, its bytes and its format
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the file is not a playable WAV file
     */
    static Header readHeader(InputStream in) throws IOException {
        final byte[] header = new byte[WavFile.HEADER_SIZE];
        int headerLength = readFully(in, header, header.length);
        try {
            return new Header(header, headerLength,
                WavFile.parseFormat(header, headerLength));
        } catch (IllegalArgumentException ex) {
            if (headerLength < WavFile.HEADER_SIZE) {
                // The file is too short to be a WAV file at all.
                throw ex;
            }
        }
        // The rest of the header is read behind the bytes already read. The
        // read fills a buffer of its own, which is then copied into place,
        // because the file read of the system library writes its data to the
        // start of the buffer it is given and would overwrite the header that
        // has to be kept.
        final byte[] wide = new byte[WavFile.MAX_HEADER_SIZE];
        System.arraycopy(header, 0, wide, 0, headerLength);
        final int extraLength = WavFile.MAX_HEADER_SIZE - headerLength;
        final byte[] extraBytes = new byte[extraLength];
        final int extra = readFully(in, extraBytes, extraLength);
        System.arraycopy(extraBytes, 0, wide, headerLength, extra);
        headerLength += extra;
        return new Header(wide, headerLength, WavFile.parseFormat(wide,
            headerLength));
    }

    /**
     * Reads exactly the requested number of bytes into the start of the
     * buffer, or up to that many if the stream ends first. A short read is not
     * the end of the stream, so a file that delivers its data in small pieces
     * still plays completely.
     *
     * @param in the stream to read from
     * @param buffer the buffer to read into
     * @param count how many bytes to read
     * @return the number of bytes read, less than asked for at the end of the
     *         stream
     */
    private static int readFully(InputStream in, byte[] buffer, int count)
        throws IOException {
        int length = 0;
        while (length < count) {
            final int n = in.read(buffer, length, count - length);
            if (n < 0) {
                break;
            }
            length += n;
        }
        return length;
    }
}
