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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import org.jnode.driver.sound.ac97.AC97API;
import org.jnode.driver.sound.ac97.AC97Constants;
import org.jnode.driver.sound.ac97.AC97Utils;
import org.jnode.driver.sound.ac97.WavFile;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.EnumArgument;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that records from the AC'97 codec and reports what it heard.
 * <p>
 * This is the way to test the capture path: it prints the input level every
 * 100ms as root mean square and peak amplitude, so speaking into the
 * microphone shows up immediately, and a silent input is reported as such
 * instead of looking like a working command.
 *
 * @author JNode contributors
 */
public class AC97CaptureCommand extends AbstractCommand {

    /**
     * Record sources the command can select.
     */
    enum Source {
        /** the dedicated microphone input */
        MIC(AC97Constants.RECORD_SELECT_MIC),
        /** the line input */
        LINE(AC97Constants.RECORD_SELECT_LINE);

        private final int select;

        Source(int select) {
            this.select = select;
        }

        public int select() {
            return select;
        }
    }

    private final IntegerArgument argDuration =
        new IntegerArgument("duration", Argument.OPTIONAL,
            "recording time in milliseconds");

    private final IntegerArgument argRate =
        new IntegerArgument("rate", Argument.OPTIONAL, "sample rate in Hz");

    private final IntegerArgument argGain =
        new IntegerArgument("gain", Argument.OPTIONAL,
            "record gain in 1.5 dB steps, 0 is maximum gain");

    private static final class SourceArgument extends EnumArgument<Source> {
        SourceArgument(String label, int flags, String description) {
            super(label, flags, Source.class, description);
        }

        @Override
        protected String argumentKind() {
            return "record source";
        }

        /**
         * Matches the record source case insensitively, so that "mic" and
         * "MIC" both work: the underlying EnumArgument matches the declared
         * enum constant names only, which are upper case.
         */
        @Override
        protected Source doAccept(Token token, int flags)
            throws CommandSyntaxException {
            return acceptSource(token.text);
        }
    }

    private final SourceArgument argSource =
        new SourceArgument("source", Argument.OPTIONAL, "record source");

    private final FileArgument argFile = new FileArgument(
        "file", Argument.OPTIONAL, "write the recording as a WAV file to this"
            + " path");

    public AC97CaptureCommand() {
        super("records from the AC'97 codec and reports the input level");
        registerArguments(argDuration, argRate, argGain, argSource, argFile);
    }

    /**
     * Parses a record source name case insensitively.
     *
     * @param text the token typed by the user
     * @return the source
     * @throws CommandSyntaxException if it is not a known source
     */
    static Source acceptSource(String text) throws CommandSyntaxException {
        for (Source source : Source.values()) {
            if (source.name().equalsIgnoreCase(text)) {
                return source;
            }
        }
        throw new CommandSyntaxException("not a valid <record source>: " + text);
    }

    public static void main(String[] args) throws Exception {
        new AC97CaptureCommand().execute(args);
    }

    @Override
    public void execute() {
        final int durationMs = argDuration.isSet() ? argDuration.getValue() : 5000;
        final int rate = argRate.isSet() ? argRate.getValue()
            : AC97Constants.SAMPLE_RATE_48000;
        final int gainSteps = argGain.isSet() ? argGain.getValue() : 0;
        final Source source = argSource.isSet() ? argSource.getValue() : Source.MIC;

        final PrintWriter out = getOutput().getPrintWriter();
        final AC97API api = AC97Utils.findDevice();
        if (api == null) {
            out.println("ac97capture: no AC'97 device found");
            exit(1);
            return;
        }

        final int frameSize = AC97Constants.PCM_FRAME_SIZE;
        final int totalFrames = (int) (((long) rate * durationMs) / 1000);
        final int windowFrames = rate / 10;
        final byte[] chunk = new byte[4096];
        // The whole recording is kept so that it can be written out as a WAV
        // file afterwards; the duration argument bounds the memory used.
        final byte[] recording = new byte[totalFrames * frameSize];

        out.println("ac97capture: recording " + durationMs + "ms at " + rate
            + "Hz, source=" + source + " gain=" + gainSteps);
        int recorded = 0;
        int windowSamples = 0;
        double windowSquares = 0;
        int windowPeak = 0;
        int windowClips = 0;
        int totalClips = 0;
        int totalPeak = 0;
        try {
            api.openCapture(rate);
            api.setRecordSource(source.select(), gainSteps);
            while (recorded < totalFrames) {
                final int frames = Math.min(chunk.length / frameSize, totalFrames
                    - recorded);
                api.readCapture(chunk, 0, frames * frameSize);
                System.arraycopy(chunk, 0, recording, recorded * frameSize,
                    frames * frameSize);
                recorded += frames;
                for (int i = 0; i + 1 < frames * frameSize; i += 2) {
                    final int sample = (chunk[i] & 0xFF) | (chunk[i + 1] << 8);
                    final int abs = sample < 0 ? -sample : sample;
                    windowSquares += (double) sample * sample;
                    windowSamples++;
                    if (abs > windowPeak) {
                        windowPeak = abs;
                    }
                    if (abs > totalPeak) {
                        totalPeak = abs;
                    }
                    if (abs >= 32767) {
                        windowClips++;
                    }
                }
                if (windowSamples >= windowFrames) {
                    reportWindow(out, recorded, windowSamples, windowSquares,
                        windowPeak, windowClips);
                    totalClips += windowClips;
                    windowSamples = 0;
                    windowSquares = 0;
                    windowPeak = 0;
                    windowClips = 0;
                }
            }
            if (windowSamples > 0) {
                reportWindow(out, recorded, windowSamples, windowSquares,
                    windowPeak, windowClips);
                totalClips += windowClips;
            }
        } catch (InterruptedException ex) {
            out.println("ac97capture: interrupted");
        } catch (Exception ex) {
            out.println("ac97capture: failed: " + ex);
        } finally {
            api.closeCapture();
        }
        if (recorded == 0) {
            out.println("ac97capture: no audio recorded");
            exit(1);
            return;
        }
        out.println("ac97capture: recorded " + recorded + " frames ("
            + (recorded * 1000 / rate) + "ms), peak=" + totalPeak
            + ", clipped=" + totalClips);
        if (argFile.isSet()) {
            writeWav(out, argFile.getValue(), recording, recorded * frameSize,
                rate);
        }
        if (totalPeak == 0) {
            out.println("ac97capture: input is digital silence - check the"
                + " record source, the gain and the host audio input");
        }
    }

    /**
     * Writes the recording as a 16 bit stereo PCM WAV file.
     */
    private void writeWav(PrintWriter out, File file, byte[] pcm, int length,
        int rate) {
        FileOutputStream fos = null;
        try {
            // The header wants bits per sample, the constant is bytes.
            final byte[] header = WavFile.createHeader(length, rate,
                AC97Constants.PCM_CHANNELS,
                AC97Constants.PCM_BYTES_PER_SAMPLE * 8);
            fos = new FileOutputStream(file);
            fos.write(header);
            fos.write(pcm, 0, length);
            out.println("ac97capture: wrote " + (length + header.length)
                + " bytes to " + file.getPath());
        } catch (IOException ex) {
            out.println("ac97capture: cannot write " + file.getPath() + ": "
                + ex.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ex) {
                    // ignored
                }
            }
        }
    }

    /**
     * Prints one level report line: root mean square, its dBFS equivalent, the
     * peak sample and the number of clipped samples of the window.
     */
    private void reportWindow(PrintWriter out, int frames, int samples,
        double squares, int peak, int clips) {
        out.println(formatLevel(frames, samples, squares, peak, clips));
    }

    /**
     * Formats one level report line, e.g.
     * <code>ac97capture: 4800 frames rms=16384 (-6.0 dBFS) peak=23170 clips=0</code>.
     * Static and pure so that the level arithmetic can be unit tested.
     *
     * @param frames  frames recorded so far
     * @param samples samples in the window
     * @param squares sum of the squared sample values of the window
     * @param peak    largest absolute sample value of the window
     * @param clips   clipped samples in the window
     */
    static String formatLevel(int frames, int samples, double squares, int peak,
        int clips) {
        final double rms = samples > 0 ? Math.sqrt(squares / samples) : 0.0;
        final String level;
        if (rms < 1.0) {
            level = "silent";
        } else {
            // dBFS: the full scale root mean square of a 16 bit signal is
            // 32768, so a square wave at full level reads 0 dBFS and a
            // sine wave at full level reads -3.0 dBFS.
            final double dbfs = 20.0 * Math.log(rms / 32768.0) / Math.log(10.0);
            final int whole = (int) (dbfs * 10.0);
            level = (whole / 10) + "." + Math.abs(whole % 10) + " dBFS";
        }
        return "ac97capture: " + frames + " frames rms=" + (int) rms + " ("
            + level + ") peak=" + peak + " clips=" + clips;
    }
}
