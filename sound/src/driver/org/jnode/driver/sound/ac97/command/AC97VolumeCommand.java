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
import org.jnode.driver.sound.ac97.MixerVolume;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that reads and changes the AC'97 mixer volumes.
 * <p>
 * Volumes are given as a percentage of full volume, because that is what a
 * user adjusting a sound card thinks in, and converted to the codec
 * attenuation steps of the register.
 *
 * @author JNode contributors
 */
public class AC97VolumeCommand extends AbstractCommand {

    /** The outputs whose volume can be changed. */
    private enum Output {
        /** master output */
        MASTER,
        /** pcm path, the samples written to the driver */
        PCM,
        /** headphone output */
        HEADPHONE
    }

    private final FlagArgument argMute = new FlagArgument(
        "mute", Argument.OPTIONAL, "mute the selected output");

    private final FlagArgument argUnmute = new FlagArgument(
        "unmute", Argument.OPTIONAL, "unmute the selected output");

    private final IntegerArgument argLevel = new IntegerArgument(
        "level", Argument.OPTIONAL, "volume in percent, 0..100");

    public AC97VolumeCommand() {
        super("reads and sets the AC'97 mixer volumes");
        registerArguments(argMute, argUnmute, argLevel);
    }

    public static void main(String[] args) throws Exception {
        new AC97VolumeCommand().execute(args);
    }

    @Override
    public void execute() {
        final PrintWriter out = getOutput().getPrintWriter();
        final AC97API api = AC97Utils.findDevice();
        if (api == null) {
            out.println("ac97volume: no AC'97 device found");
            exit(1);
            return;
        }
        if (argMute.isSet() && argUnmute.isSet()) {
            out.println("ac97volume: --mute and --unmute are exclusive");
            exit(1);
            return;
        }
        if (!argLevel.isSet() && !argMute.isSet() && !argUnmute.isSet()) {
            // No change requested: report the current state.
            report(out, "master", api.getMasterVolume());
            report(out, "pcm", api.getPcmVolume());
            report(out, "headphone", api.getHeadphoneVolume());
            return;
        }
        final boolean mute = argMute.isSet();
        final int steps = argLevel.isSet() ? MixerVolume.percentToSteps(
            argLevel.getValue()) : -1;
        apply(out, Output.MASTER, api, steps, mute, argMute.isSet()
            || argUnmute.isSet());
        apply(out, Output.PCM, api, steps, mute, argMute.isSet()
            || argUnmute.isSet());
        // A change is only useful when it is audible, so the setting is
        // reported back from the codec.
        report(out, "master", api.getMasterVolume());
        report(out, "pcm", api.getPcmVolume());
        report(out, "headphone", api.getHeadphoneVolume());
    }

    /**
     * Applies a level or mute change to one output.
     */
    private void apply(PrintWriter out, Output output, AC97API api, int steps,
        boolean mute, boolean changeMute) {
        MixerVolume volume = getVolume(api, output);
        if (changeMute) {
            volume = volume.mute(mute);
        }
        if (steps >= 0) {
            volume = volume.level(steps, steps);
        }
        setVolume(api, output, volume);
    }

    private void report(PrintWriter out, String name, MixerVolume volume) {
        out.println("ac97volume: " + name + "=" + volume
            + (volume.isMuted() ? "" : " (" + (int) volume.getLeftDb() + " dB, "
                + (int) volume.getRightDb() + " dB)"));
    }

    private MixerVolume getVolume(AC97API api, Output output) {
        switch (output) {
            case MASTER:
                return api.getMasterVolume();
            case PCM:
                return api.getPcmVolume();
            default:
                return api.getHeadphoneVolume();
        }
    }

    private void setVolume(AC97API api, Output output, MixerVolume volume) {
        switch (output) {
            case MASTER:
                api.setMasterVolume(volume);
                break;
            case PCM:
                api.setPcmVolume(volume);
                break;
            default:
                api.setHeadphoneVolume(volume);
                break;
        }
    }
}
