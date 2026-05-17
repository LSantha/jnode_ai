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

import java.io.IOException;
import java.nio.CharBuffer;

import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.textscreen.KeyboardReader;
import org.jnode.driver.serial.SerialPortAPI;

/**
 * A raw, zero-overhead reader for agent-based interaction over a serial port.
 * Bypasses the event queue, line editing, and history buffering of the standard
 * {@link KeyboardReader}.
 *
 * @author JNode.org
 */
public class RawKeyboardReader extends KeyboardReader {

    private final SerialPortAPI serialPort;

    public RawKeyboardReader(SerialPortAPI serialPort, TextConsole console) {
        // Pass null for KeyboardHandler to avoid allocating event queues
        super(null, console);
        this.serialPort = serialPort;
    }

    @Override
    public int read() throws IOException {
        return serialPort.readSingle();
    }

    @Override
    public int read(char[] buff, int off, int len) throws IOException {
        if (len == 0) return 0;
        // Read exactly one character to remain highly responsive to the shell reader
        int b = serialPort.readSingle();
        if (b == -1) return -1;
        buff[off] = (char) b;
        return 1;
    }

    @Override
    public int read(char[] buff) throws IOException {
        return read(buff, 0, buff.length);
    }

    @Override
    public int read(CharBuffer target) throws IOException {
        int len = target.remaining();
        if (len == 0) return 0;
        int b = serialPort.readSingle();
        if (b == -1) return -1;
        target.put((char) b);
        return 1;
    }

    @Override
    public boolean ready() throws IOException {
        return serialPort.isDataAvailable();
    }

    @Override
    public void close() throws IOException {
        // No-op for raw port reader
    }
}
