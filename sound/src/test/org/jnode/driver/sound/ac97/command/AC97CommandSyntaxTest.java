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
import static org.junit.Assert.assertNotNull;

import org.jnode.shell.Command;
import org.jnode.shell.CommandLine;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.OptionSetSyntax;
import org.jnode.shell.syntax.OptionSyntax;
import org.jnode.shell.syntax.SequenceSyntax;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.junit.Test;

/**
 * Host-runnable test for the command line of the AC'97 commands.
 * <p>
 * The <code>--device</code> option is the only way to reach a second codec on
 * a machine that has one, so every command must take it from the shell and
 * bind it to the argument that selects the device, and must still parse when it
 * is left out. The syntaxes here are built the way the plugin descriptor
 * declares them: an option set in front of the positional arguments.
 *
 * @author JNode contributors
 */
public class AC97CommandSyntaxTest {

    /**
     * Parses a command line against the given syntax, and returns the command
     * with its arguments bound.
     */
    private Command parse(Command shellCommand, Syntax syntax, String... args)
        throws Exception {
        final CommandLine commandLine = new CommandLine(
            shellCommand.getClass().getSimpleName(), args);
        shellCommand.getArgumentBundle().parse(commandLine,
            new SyntaxBundle("cmd", syntax));
        return shellCommand;
    }

    /**
     * Reads the device index a command parsed, which is 0 when the option was
     * left out.
     */
    private int device(Command cmd) {
        final IntegerArgument arg =
            (IntegerArgument) cmd.getArgumentBundle().getArgument("device");
        return arg.isSet() ? arg.getValue() : 0;
    }

    @Test
    public void testCommandsRegisterTheDeviceArgument() {
        // The option set of the descriptor refers to the arguments by label,
        // so a command that does not register one cannot be selected at all.
        final Command[] commands = {new AC97CaptureCommand(), new AC97InfoCommand(),
            new AC97PlayCommand(), new AC97RegCommand(), new AC97VolumeCommand(),
            new AC97WavCommand()};
        for (Command cmd : commands) {
            assertNotNull("device argument missing in " + cmd.getClass()
                .getName(), cmd.getArgumentBundle().getArgument("device"));
        }
    }

    /**
     * The syntax of ac97wav: one file argument behind an option set.
     */
    private Syntax wavSyntax() {
        return new SequenceSyntax(new OptionSetSyntax(new OptionSyntax("device",
            "device", 'd')), new ArgumentSyntax("file"));
    }

    @Test
    public void testWavWithoutTheDeviceOption() throws Exception {
        final Command cmd = parse(new AC97WavCommand(), wavSyntax(),
            "/jnode/sound.wav");
        assertEquals("the device defaults to the first one", 0, device(cmd));
    }

    @Test
    public void testWavWithTheDeviceOption() throws Exception {
        final Command cmd = parse(new AC97WavCommand(), wavSyntax(), "--device",
            "1", "/jnode/sound.wav");
        assertEquals(1, device(cmd));
        final FileArgument fileArg =
            (FileArgument) cmd.getArgumentBundle().getArgument("file");
        assertEquals("/jnode/sound.wav", fileArg.getValue().getPath());
    }

    @Test
    public void testWavWithTheShortDeviceOption() throws Exception {
        // The short name has to reach the same argument as the long one.
        final Command cmd = parse(new AC97WavCommand(), wavSyntax(), "-d", "3",
            "/jnode/sound.wav");
        assertEquals(3, device(cmd));
    }

    @Test
    public void testPlayTakesTheDeviceOption() throws Exception {
        final Syntax syntax = new SequenceSyntax(new OptionSetSyntax(
            new OptionSyntax("device", "device", 'd')), new ArgumentSyntax(
            "frequency"), new ArgumentSyntax("duration"));
        final Command cmd = parse(new AC97PlayCommand(), syntax, "--device", "2",
            "440", "500");
        assertEquals(2, device(cmd));
        assertEquals("the positional arguments survive the option", Integer
            .valueOf(440), cmd.getArgumentBundle().getArgument("frequency")
            .getValue());
        assertEquals(Integer.valueOf(500), cmd.getArgumentBundle().getArgument(
            "duration").getValue());
    }

    @Test
    public void testCaptureTakesTheLongDeviceOptionOnly() throws Exception {
        // The capture command already uses -d for the duration, so its device
        // option has a long name only, exactly as the descriptor declares it.
        final Syntax syntax = new SequenceSyntax(new OptionSetSyntax(
            new OptionSyntax("device", "device"), new OptionSyntax("duration",
                "duration", 'd'), new OptionSyntax("rate", "rate", 'r')));
        final Command cmd = parse(new AC97CaptureCommand(), syntax, "--duration",
            "200", "--rate", "48000", "--device", "1");
        assertEquals(1, device(cmd));
        assertEquals(Integer.valueOf(200), cmd.getArgumentBundle().getArgument(
            "duration").getValue());
        assertEquals(Integer.valueOf(48000), cmd.getArgumentBundle().getArgument(
            "rate").getValue());
    }

    @Test
    public void testDeviceOptionNeedsAnIndex() throws Exception {
        try {
            parse(new AC97WavCommand(), wavSyntax(), "--device");
            org.junit.Assert.fail("expected an exception for a missing index");
        } catch (CommandSyntaxException ex) {
            // expected
        }
    }

    @Test
    public void testUnknownDeviceOptionIsRejected() throws Exception {
        try {
            parse(new AC97WavCommand(), wavSyntax(), "--device", "dvorak",
                "/jnode/sound.wav");
            org.junit.Assert.fail("expected an exception for a bad index");
        } catch (CommandSyntaxException ex) {
            // expected
        }
    }

    @Test
    public void testMissingFileIsRejected() throws Exception {
        // The file argument stays mandatory for ac97wav, option or not.
        try {
            parse(new AC97WavCommand(), wavSyntax(), "--device", "0");
            org.junit.Assert.fail("expected an exception for a missing file");
        } catch (CommandSyntaxException ex) {
            // expected
        }
    }
}
