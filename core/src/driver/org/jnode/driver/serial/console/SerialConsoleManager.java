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

package org.jnode.driver.serial.console;

import java.io.Reader;

import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.console.ConsoleException;
import org.jnode.driver.console.textscreen.KeyboardHandler;
import org.jnode.driver.console.textscreen.KeyboardReader;
import org.jnode.driver.console.textscreen.TextScreenConsole;
import org.jnode.driver.console.textscreen.TextScreenConsoleManager;

/**
 * Console manager for serial port consoles. Extends {@link TextScreenConsoleManager}
 * to provide serial-port-backed text screens and keyboard input.
 * <p>
 * This class follows the same pattern as the telnetd's {@code RemoteConsoleManager},
 * but uses a serial port instead of a telnet connection for I/O.
 *
 * @author JNode.org
 */
public class SerialConsoleManager extends TextScreenConsoleManager {

    private final SerialTextScreenManager textScreenManager;
    private SerialPortAPI serialPort;

    /**
     * Construct a serial console manager.
     *
     * @throws ConsoleException if initialization fails.
     */
    public SerialConsoleManager() throws ConsoleException {
        super();
        this.textScreenManager = new SerialTextScreenManager();
    }

    /**
     * Set the serial port to use for console I/O.
     *
     * @param serialPort the serial port API.
     */
    public void setSerialPort(SerialPortAPI serialPort) {
        this.serialPort = serialPort;
        this.textScreenManager.setSerialPort(serialPort);
    }

    /**
     * Create a Reader for the console that reads from the serial port.
     * Uses a {@link SerialKeyboardHandler} to convert serial input bytes
     * into keyboard events, wrapped in a {@link KeyboardReader} for
     * line editing, completion, and history support.
     */
    @Override
    protected Reader getReader(int options, TextScreenConsole console) {
        KeyboardHandler kbHandler = new SerialKeyboardHandler(serialPort);
        return new KeyboardReader(kbHandler, console);
    }

    /**
     * Return the serial-port-backed text screen manager.
     */
    @Override
    protected SerialTextScreenManager getTextScreenManager() {
        return textScreenManager;
    }
}
