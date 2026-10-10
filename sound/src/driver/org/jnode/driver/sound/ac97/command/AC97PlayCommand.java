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

import org.jnode.driver.sound.ac97.AC97Utils;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that plays a square wave tone through the AC'97 codec.
 * <p>
 * Usage: <code>ac97play [-f frequency] [-l duration]</code>.
 *
 * @author JNode contributors
 */
public class AC97PlayCommand extends AbstractCommand {

    private static final int DEFAULT_FREQUENCY = 440;

    private static final int DEFAULT_DURATION = 500;

    private final IntegerArgument argFrequency = new IntegerArgument(
        "frequency", Argument.OPTIONAL, "tone frequency in Hz");

    private final IntegerArgument argDuration = new IntegerArgument(
        "duration", Argument.OPTIONAL, "duration in milliseconds");

    public AC97PlayCommand() {
        super("plays a tone through the AC'97 codec");
        registerArguments(argFrequency, argDuration);
    }

    public static void main(String[] args) throws Exception {
        new AC97PlayCommand().execute(args);
    }

    @Override
    public void execute() {
        final int frequency = argFrequency.isSet() ? argFrequency.getValue()
            : DEFAULT_FREQUENCY;
        final int duration = argDuration.isSet() ? argDuration.getValue()
            : DEFAULT_DURATION;
        AC97Utils.playTone(frequency, duration);
    }
}
