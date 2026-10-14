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

import org.jnode.shell.AbstractCommand;
import org.jnode.shell.Command;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.OptionSetSyntax;
import org.jnode.shell.syntax.OptionSyntax;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.junit.Test;

/**
 * Host-runnable test for the command line of ac97volume.
 * <p>
 * The output selector is what lets a user change the headphone output on its
 * own, so it must parse from the shell and bind to the argument the command
 * reads, while the command still works without it.
 *
 * @author JNode contributors
 */
public class AC97VolumeCommandTest {

    /**
     * The syntax of ac97volume: an option set, no positional arguments.
     */
    private Syntax syntax(String optionName) {
        return new OptionSetSyntax(new OptionSyntax("mute", optionName, 'm'),
            new OptionSyntax("unmute", "unmute", 'u'), new OptionSyntax(
                "level", "level", 'l'), new OptionSyntax("output", "output",
                'o'), new OptionSyntax("device", "device", 'd'));
    }

    private Command parse(Command shellCommand, Syntax syntax, String... args)
        throws Exception {
        final CommandLine commandLine = new CommandLine(shellCommand
            .getClass().getSimpleName(), args);
        shellCommand.getArgumentBundle().parse(commandLine,
            new SyntaxBundle("cmd", syntax));
        return shellCommand;
    }

    private AC97VolumeCommand volume() {
        return new AC97VolumeCommand();
    }

    @Test
    public void testWithoutTheSelector() throws Exception {
        final Command cmd = parse(volume(), syntax("mute"));
        // The selector is unset, so the command falls back to its old
        // behaviour of touching master and pcm together.
        final AbstractCommand ac = (AbstractCommand) cmd;
        assertEquals("the output is unset", 0, ac.getArgumentBundle()
            .getArgument("output").getValues().length);
    }

    @Test
    public void testTheSelectorBindsToTheArgument() throws Exception {
        final Command cmd = parse(volume(), syntax("mute"), "--output",
            "headphone", "--level", "50");
        final AbstractCommand ac = (AbstractCommand) cmd;
        assertEquals(AC97VolumeCommand.Output.HEADPHONE, ac.getArgumentBundle()
            .getArgument("output").getValue());
        assertEquals(50, ac.getArgumentBundle().getArgument("level").getValue());
        // And the selector does not disturb the mute flag.
        assertEquals(0, ac.getArgumentBundle().getArgument("mute").getValues()
            .length);
    }

    @Test
    public void testTheSelectorIsCaseInsensitive() throws Exception {
        final Command cmd = parse(volume(), syntax("mute"), "-o", "PCM");
        final AbstractCommand ac = (AbstractCommand) cmd;
        assertEquals(AC97VolumeCommand.Output.PCM, ac.getArgumentBundle()
            .getArgument("output").getValue());
    }

    @Test
    public void testAnUnknownOutputIsRejected() throws Exception {
        try {
            parse(volume(), syntax("mute"), "--output", "bass");
            org.junit.Assert.fail("expected an exception for an unknown output");
        } catch (CommandSyntaxException ex) {
            // expected
        }
    }

    @Test
    public void testTheSelectorWorksWithTheDeviceOption() throws Exception {
        final Command cmd = parse(volume(), syntax("mute"), "--device", "1",
            "--output", "master", "--mute");
        final AbstractCommand ac = (AbstractCommand) cmd;
        assertEquals(AC97VolumeCommand.Output.MASTER, ac.getArgumentBundle()
            .getArgument("output").getValue());
        assertEquals(1, ac.getArgumentBundle().getArgument("device").getValue());
        assertEquals("mute is bound", 1, ac.getArgumentBundle()
            .getArgument("mute").getValues().length);
    }
}
