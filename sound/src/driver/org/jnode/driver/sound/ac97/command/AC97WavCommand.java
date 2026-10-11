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

    public AC97WavCommand() {
        super("plays a PCM WAV file through the AC'97 codec");
        registerArguments(argFile);
    }

    public static void main(String[] args) throws Exception {
        new AC97WavCommand().execute(args);
    }

    @Override
    public void execute() {
        final PrintWriter out = getOutput().getPrintWriter();
        final File file = argFile.getValue();
        final AC97API api = AC97Utils.findDevice();
        if (api == null) {
            out.println("ac97wav: no AC'97 device found");
            exit(1);
            return;
        }

        final int chunkBytes = 8192;
        final byte[] raw = new byte[chunkBytes];
        int played = 0;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            // A canonical header is 44 bytes. Files with extra metadata are
            // retried with a bigger buffer, and the parse stops at the data
            // chunk, so only the data chunk is played.
            WavFile.Format format = null;
            byte[] header = new byte[WavFile.HEADER_SIZE];
            int headerLength = readFully(in, header, header.length);
            int buffered = 0;
            try {
                format = WavFile.parseFormat(header, headerLength);
            } catch (IllegalArgumentException ex) {
                if (headerLength < WavFile.HEADER_SIZE) {
                    throw ex;
                }
                header = new byte[WavFile.MAX_HEADER_SIZE];
                headerLength = readFully(in, header, header.length);
                format = WavFile.parseFormat(header, headerLength);
            }
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
            if (format.getDataOffset() < headerLength) {
                buffered = headerLength - format.getDataOffset();
                if (buffered > format.getDataLength()) {
                    buffered = format.getDataLength();
                }
                played += playBuffer(api, format, header,
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
            out.println("ac97wav: played " + played + " frames ("
                + (played * 1000 / format.getSampleRate()) + "ms)");
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
            api.close();
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
     * Reads exactly the requested number of bytes, or up to that many if the
     * stream ends first. A short read is not the end of the stream, so a file
     * that delivers its data in small pieces still plays completely.
     */
    private int readFully(InputStream in, byte[] buffer, int count)
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
