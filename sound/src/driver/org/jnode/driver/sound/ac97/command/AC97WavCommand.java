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
            if (format.getSampleRate() != AC97Constants.SAMPLE_RATE_48000
                && !api.isVariableRateSupported()) {
                out.println("ac97wav: codec has no variable rate audio and the"
                    + " file is not 48000 Hz; convert the file to 48000 Hz");
                exit(1);
                return;
            }
            api.open(format.getSampleRate());
            final byte[] pcm = new byte[WavFile.convertedLength(chunkBytes, 1, 8)];

            // PCM bytes that were read as part of the header buffer, either
            // because the header is longer than 44 bytes or because a whole
            // chunk was read, are played before the rest of the data chunk.
            long remaining = format.getDataLength();
            if (format.getDataOffset() < headerLength) {
                buffered = headerLength - format.getDataOffset();
                if (buffered > format.getDataLength()) {
                    buffered = format.getDataLength();
                }
                played += playBuffer(out, api, format, header,
                    format.getDataOffset(), buffered, raw, pcm);
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
                played += playBuffer(out, api, format, raw, 0, read, raw, pcm);
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
    private int playBuffer(PrintWriter out, AC97API api, WavFile.Format format,
        byte[] src, int srcOffset, int length, byte[] scratch, byte[] pcm)
        throws InterruptedException, TimeoutException {
        final int len = WavFile.convertToStereo16(src, srcOffset, length, pcm, 0,
            format.getChannels(), format.getBitsPerSample());
        if (len > 0) {
            api.write(pcm, 0, len);
        }
        return len / AC97Constants.PCM_FRAME_SIZE;
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
