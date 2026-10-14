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
import org.jnode.driver.sound.ac97.AC97Constants;
import org.jnode.driver.sound.ac97.AC97Utils;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.IntegerArgument;

/**
 * Shell command that reads and writes the codec (mixer) registers of an
 * AC'97 device.
 * <p>
 * This is how a codec is investigated: without arguments it dumps the
 * registers the driver itself uses, so a volume or power problem can be read
 * off directly. A single register can be read or written by index; the
 * registers are 16 bit wide and even indexed, and writing the wrong one can
 * silence the codec, so --set exists for debugging and manufacturer specific
 * code rather than for normal use.
 *
 * @author JNode contributors
 */
public class AC97RegCommand extends AbstractCommand {

    /** The registers dumped by default. */
    private static final int[] DUMP_REGISTERS = {AC97Constants.NAM_RESET,
        AC97Constants.NAM_MASTER_VOLUME, AC97Constants.NAM_HEADPHONE_VOLUME,
        AC97Constants.NAM_PCM_OUT_VOLUME, AC97Constants.NAM_RECORD_SELECT,
        AC97Constants.NAM_RECORD_GAIN, AC97Constants.NAM_POWERDOWN_CTRL_STAT,
        AC97Constants.NAM_EXTENDED_AUDIO_ID,
        AC97Constants.NAM_EXTENDED_AUDIO_STATUS_CTRL,
        AC97Constants.NAM_PCM_FRONT_DAC_RATE, AC97Constants.NAM_VENDOR_ID1,
        AC97Constants.NAM_VENDOR_ID2};

    /**
     * Register index or register value. Codec registers are written in hex
     * everywhere the documentation and the driver's own constants use, so the
     * debugging workflow has to be able to type 0x2E; a bare number stays
     * decimal.
     */
    static final class RegisterArgument extends IntegerArgument {
        RegisterArgument(String label, int flags, String description) {
            super(label, flags, description);
        }

        @Override
        protected Integer doAccept(Token token, int tokenFlags)
            throws CommandSyntaxException {
            final String text = token.text;
            final boolean hex = text.startsWith("0x") || text.startsWith("0X");
            try {
                return Integer.valueOf(hex ? text.substring(2) : text, hex
                    ? 16 : 10);
            } catch (NumberFormatException ex) {
                throw new CommandSyntaxException(
                    "not a decimal or 0x hex number: " + text);
            }
        }

        @Override
        protected String argumentKind() {
            return "register value";
        }
    }

    private final IntegerArgument argGet =
        new RegisterArgument("get", Argument.OPTIONAL,
            "read a single register by index");

    private final IntegerArgument argSet =
        new RegisterArgument("set", Argument.OPTIONAL,
            "write a register by index");

    private final IntegerArgument argValue =
        new RegisterArgument("value", Argument.OPTIONAL,
            "the 16 bit value for --set");

    private final IntegerArgument argDevice = new IntegerArgument(
        "device", Argument.OPTIONAL, "index of the AC'97 device to use");

    public AC97RegCommand() {
        super("reads and writes the AC'97 codec registers");
        registerArguments(argGet, argSet, argValue, argDevice);
    }

    public static void main(String[] args) throws Exception {
        new AC97RegCommand().execute(args);
    }

    @Override
    public void execute() {
        final PrintWriter out = getOutput().getPrintWriter();
        final int deviceIndex = argDevice.isSet() ? argDevice.getValue()
            : 0;
        final AC97API api = AC97Utils.findDevice(deviceIndex);
        if (api == null) {
            out.println("ac97reg: " + AC97Utils.describeNoDevice(deviceIndex));
            exit(1);
            return;
        }
        if (argGet.isSet()) {
            final int index = argGet.getValue();
            report(out, index, readChecked(out, api, index));
            return;
        }
        if (argSet.isSet()) {
            final int index = argSet.getValue();
            if (!argValue.isSet()) {
                out.println("ac97reg: --set needs a --value");
                exit(1);
                return;
            }
            write(out, api, index, argValue.getValue());
            report(out, index, readChecked(out, api, index));
            return;
        }
        // No single register: dump what the driver uses.
        for (int index : DUMP_REGISTERS) {
            report(out, index, readChecked(out, api, index));
        }
        out.println("ac97reg: codec id=0x"
            + java.lang.Integer.toHexString(api.getCodecId()));
    }

    /**
     * Reads a register and reports why it could not be read, if so. The exit
     * throws, so there is nothing to rethrow: the caller never sees the
     * exception again.
     */
    private int readChecked(PrintWriter out, AC97API api, int index) {
        try {
            return api.readCodecRegister(index);
        } catch (IllegalArgumentException ex) {
            out.println("ac97reg: " + ex.getMessage());
            exit(1);
            return 0;
        }
    }

    /**
     * Writes a register and reports why it could not be written, if so.
     */
    private void write(PrintWriter out, AC97API api, int index, int value) {
        try {
            api.writeCodecRegister(index, value);
        } catch (IllegalArgumentException ex) {
            out.println("ac97reg: " + ex.getMessage());
            exit(1);
        }
    }

    /**
     * Parses a register index or value the way the command does, for the
     * benefit of a user who wants to know what is accepted.
     *
     * @param text a decimal number, or a hex number with a 0x prefix
     * @return the value it names
     * @throws CommandSyntaxException if the text is not such a number
     */
    static int acceptRegister(String text) throws CommandSyntaxException {
        final boolean hex = text.startsWith("0x") || text.startsWith("0X");
        try {
            return Integer.parseInt(hex ? text.substring(2) : text, hex ? 16 : 10);
        } catch (NumberFormatException ex) {
            throw new CommandSyntaxException(
                "not a decimal or 0x hex number: " + text);
        }
    }

    private void report(PrintWriter out, int index, int value) {
        out.println("ac97reg: 0x" + java.lang.Integer.toHexString(index) + " = 0x"
            + java.lang.Integer.toHexString(value & 0xFFFF));
    }
}
