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

package org.jnode.shell.command.driver.console;

import java.io.PrintWriter;

import org.jnode.driver.DeviceUtils;
import org.jnode.driver.console.ConsoleManager;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.serial.console.SerialConsoleManager;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.CommandShell;
import org.jnode.shell.ShellException;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.DeviceArgument;
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.driver.serial.console.SerialAgentConsole;
import org.jnode.driver.console.CompletionInfo;

/**
 * Shell command to start an interactive JNode command shell on a serial port.
 * <p>
 * The serial port and baud rate are configurable. The command blocks until
 * the user types 'exit' in the serial console.
 * <p>
 * Usage: {@code serialconsole [--port serial0] [--baud 115200]}
 * <p>
 * Example with QEMU: Start QEMU with {@code -serial stdio} or {@code -serial pty},
 * then run {@code serialconsole} from the VGA console. The serial terminal will
 * present a full interactive JNode shell with line editing, tab completion,
 * and command history.
 *
 * @author JNode.org
 */
public class SerialConsoleCommand extends AbstractCommand {

    /**
     * Baud rate divisor constants for common baud rates.
     * The actual rate is 115200 / divisor.
     */
    private static final int DIVISOR_115200 = 1;
    private static final int DIVISOR_57600 = 2;
    private static final int DIVISOR_38400 = 3;
    private static final int DIVISOR_19200 = 6;
    private static final int DIVISOR_9600 = 12;
    private static final int DIVISOR_4800 = 24;
    private static final int DIVISOR_2400 = 48;
    private static final int DIVISOR_1200 = 96;

    private static final int DEFAULT_BAUD = 115200;
    private static final String DEFAULT_PORT = "serial0";

    private final DeviceArgument ARG_PORT = new DeviceArgument(
        "port", Argument.OPTIONAL,
        "the serial port device name (default: serial1)",
        SerialPortAPI.class);

    private final IntegerArgument ARG_BAUD = new IntegerArgument(
        "baud", Argument.OPTIONAL,
        "the baud rate (default: 115200). Supported: 115200, 57600, 38400, 19200, 9600, 4800, 2400, 1200") {
        @Override
        public void doComplete(CompletionInfo completions, String partial, int flags) {
            final String[] rates = {"115200", "57600", "38400", "19200", "9600", "4800", "2400", "1200"};
            for (String rate : rates) {
                if (rate.startsWith(partial)) {
                    completions.addCompletion(rate);
                }
            }
        }
    };

    private final FlagArgument ARG_AGENT = new FlagArgument(
        "agent", Argument.OPTIONAL,
        "enable agent mode (raw text, no VT100 escapes, no local echo)");

    public SerialConsoleCommand() {
        super("Start an interactive shell on a serial port");
        registerArguments(ARG_PORT, ARG_BAUD, ARG_AGENT);
    }

    public static void main(String[] args) throws Exception {
        new SerialConsoleCommand().execute(args);
    }

    @Override
    public void execute() throws Exception {
        final PrintWriter out = getOutput().getPrintWriter();
        final PrintWriter err = getError().getPrintWriter();

        final String portName = ARG_PORT.isSet() ? ARG_PORT.getValue().getId() : DEFAULT_PORT;
        final int baudRate = ARG_BAUD.isSet() ? ARG_BAUD.getValue() : DEFAULT_BAUD;

        // Validate and convert baud rate to divisor
        final int divisor = baudRateToDivisor(baudRate);
        if (divisor < 0) {
            err.println("Unsupported baud rate: " + baudRate);
            err.println("Supported rates: 115200, 57600, 38400, 19200, 9600, 4800, 2400, 1200");
            exit(1);
            return;
        }

        // Look up the serial port device
        final SerialPortAPI serialPort;
        try {
            serialPort = (SerialPortAPI) DeviceUtils.getAPI(portName, SerialPortAPI.class);
        } catch (Exception e) {
            err.println("Cannot access serial port '" + portName + "': " + e.toString());
            e.printStackTrace(err);
            exit(1);
            return;
        }

        // Configure the serial port
        serialPort.configure(divisor);

        out.println("Starting serial console on " + portName + " at " + baudRate + " baud...");
        out.println("Connect a terminal to the serial port to access the shell.");
        out.println("Type 'exit' in the serial console to return here.");

        // Create the serial console manager and set up the console
        SerialConsoleManager consoleManager;
        try {
            consoleManager = new SerialConsoleManager();
        } catch (Exception e) {
            err.println("Failed to create serial console manager: " + e.getMessage());
            exit(1);
            return;
        }
        consoleManager.setSerialPort(serialPort);

        if (ARG_AGENT.isSet()) {
            // Agent mode: Use the raw SerialAgentConsole
            final TextConsole console = new SerialAgentConsole(consoleManager, "agent-" + portName, serialPort);
            try {
                CommandShell commandShell = new CommandShell(console);
                commandShell.setProperty(CommandShell.PROMPT_PROPERTY_NAME, "\n[JNODE_AGENT_READY]\n");
                commandShell.run();
            } catch (ShellException e) {
                err.println("Serial agent shell error: " + e.getMessage());
            }
            return;
        } else {
            // Send a VT100 clear screen and welcome message to the serial terminal
            sendWelcome(serialPort, portName, baudRate);
        }

        // Create a scrollable text console backed by the serial port
        final TextConsole console = (TextConsole) consoleManager.createConsole(
            "serial-" + portName,
            ConsoleManager.CreateOptions.TEXT | ConsoleManager.CreateOptions.SCROLLABLE);
        consoleManager.focus(console);

        // Create and run a command shell on the serial console.
        // This blocks until the user types 'exit'.
        try {
            CommandShell commandShell = new CommandShell(console);
            commandShell.run();
        } catch (ShellException e) {
            err.println("Serial shell error: " + e.getMessage());
        } finally {
            // Clean up
            consoleManager.unregisterConsole(console);
            console.close();
            out.println("Serial console on " + portName + " closed.");
        }
    }

    /**
     * Send a welcome/clear screen sequence to the serial terminal.
     */
    private void sendWelcome(SerialPortAPI port, String portName, int baudRate) {
        // VT100:
        //   \033[?7l  - disable auto-wrap (JNode handles its own wrapping)
        //   \033[1;25r - set scrolling region to rows 1-25
        //   \033[2J   - clear screen
        //   \033[H    - home cursor
        writeString(port, "\033[?7l\033[1;25r\033[2J\033[H");
        writeString(port, "JNode Serial Console (" + portName + " @ " + baudRate + " baud)\r\n");
        writeString(port, "Type 'exit' to disconnect.\r\n\r\n");
    }

    /**
     * Write a string to the serial port byte by byte.
     */
    private void writeString(SerialPortAPI port, String s) {
        for (int i = 0; i < s.length(); i++) {
            port.writeSingle(s.charAt(i));
        }
    }

    /**
     * Convert a baud rate to a UART divisor value.
     *
     * @param baudRate the desired baud rate.
     * @return the divisor, or -1 if the baud rate is not supported.
     */
    private static int baudRateToDivisor(int baudRate) {
        switch (baudRate) {
            case 115200: return DIVISOR_115200;
            case 57600:  return DIVISOR_57600;
            case 38400:  return DIVISOR_38400;
            case 19200:  return DIVISOR_19200;
            case 9600:   return DIVISOR_9600;
            case 4800:   return DIVISOR_4800;
            case 2400:   return DIVISOR_2400;
            case 1200:   return DIVISOR_1200;
            default:     return -1;
        }
    }
}
