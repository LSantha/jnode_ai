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

import java.io.PrintWriter;

import org.jnode.driver.sound.ac97.AC97API;
import org.jnode.driver.sound.ac97.AC97Utils;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that shows and tunes the playback ring of an AC'97 device.
 * <p>
 * Without arguments it prints the ring geometry, the ring depth in
 * milliseconds, the sample format, the sample rate and the microphone gain,
 * which is what a user or a bug report needs to know about the driver state.
 * The buffer size and the microphone gain can be changed; the ring is
 * reallocated for that, which is only possible while playback is closed.
 *
 * @author JNode contributors
 */
public class AC97InfoCommand extends AbstractCommand {

    private final IntegerArgument argBufferSize =
        new IntegerArgument("buffer-size", Argument.OPTIONAL,
            "frames per playback sample buffer, 1..2048");

    private final IntegerArgument argMicGain =
        new IntegerArgument("mic-gain", Argument.OPTIONAL,
            "microphone gain in 1.5 dB steps above the input gain, 0..15");

    private final IntegerArgument argDevice = new IntegerArgument(
        "device", Argument.OPTIONAL, "index of the AC'97 device to use");

    public AC97InfoCommand() {
        super("shows and tunes the AC'97 playback ring");
        registerArguments(argBufferSize, argMicGain, argDevice);
    }

    public static void main(String[] args) throws Exception {
        new AC97InfoCommand().execute(args);
    }

    @Override
    public void execute() {
        final PrintWriter out = getOutput().getPrintWriter();
        final int deviceIndex = argDevice.isSet() ? argDevice.getValue()
            : 0;
        final AC97API api = AC97Utils.findDevice(deviceIndex);
        if (api == null) {
            out.println("ac97info: " + AC97Utils.describeNoDevice(deviceIndex));
            exit(1);
            return;
        }
        if (argBufferSize.isSet()) {
            try {
                api.setPlaybackBufferSize(argBufferSize.getValue());
            } catch (IllegalArgumentException ex) {
                out.println("ac97info: " + ex.getMessage());
                exit(1);
                return;
            } catch (IllegalStateException ex) {
                out.println("ac97info: " + ex.getMessage());
                exit(1);
                return;
            }
        }
        if (argMicGain.isSet()) {
            try {
                api.setMicGainSteps(argMicGain.getValue());
            } catch (IllegalArgumentException ex) {
                out.println("ac97info: " + ex.getMessage());
                exit(1);
                return;
            }
        }
        out.println("ac97info: playback open=" + api.isOpen());
        out.println("ac97info: ring=" + api.getPlaybackBufferSize()
            + " frames per buffer, " + api.getPlaybackLatencyMs() + "ms deep");
        out.println("ac97info: format=" + api.getSpeakerFormat() + " rate="
            + api.getSampleRate() + "Hz recordRate=" + api.getCaptureSampleRate()
            + "Hz (variable rate supported="
            + api.isVariableRateSupported() + ")");
        out.println("ac97info: capture open=" + api.isCaptureOpen()
            + ", buffers waiting=" + api.getCapturedBuffers()
            + ", microphone gain=" + api.getMicGainSteps() + " steps");
        out.println("ac97info: codec id=0x"
            + Integer.toHexString(api.getCodecId()) + " codecErrors="
            + api.getCodecErrors());
    }
}
