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

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.LinkedList;

import org.apache.log4j.Logger;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.textscreen.KeyboardReader;
import org.jnode.driver.input.KeyboardEvent;
import org.jnode.driver.serial.SerialPortAPI;

/**
 * A raw, zero-overhead reader for agent-based interaction over a serial port.
 * Bypasses the event queue, line editing, and history buffering of the standard
 * {@link KeyboardReader}.
 * <p>
 * A daemon pump thread drains the serial port continuously. Control characters
 * Ctrl-C (ETX, 0x03) and Ctrl-Z (SUB, 0x1A) are translated into keyboard events
 * dispatched to the console's listeners, so the shell's job control
 * (AsyncCommandInvoker) can interrupt or background a running command even
 * when the shell itself is blocked waiting for it -- exactly like on VGA.
 * All other bytes are queued for the shell's input reads.
 *
 * @author JNode.org
 */
public class RawKeyboardReader extends KeyboardReader {

    private static final Logger log = Logger.getLogger(RawKeyboardReader.class);

    private final SerialPortAPI serialPort;
    private final TextConsole agentConsole;
    private final LinkedList<Byte> queue = new LinkedList<Byte>();

    public RawKeyboardReader(SerialPortAPI serialPort, TextConsole console) {
        // Pass null for KeyboardHandler to avoid allocating event queues
        super(null, console);
        this.serialPort = serialPort;
        this.agentConsole = console;
        Thread pump = new Thread(new InputPump(), "serial-agent-input-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Continuously drain the port: control keys become events, the rest
     * is queued for read().
     */
    private final class InputPump implements Runnable {
        public void run() {
            while (true) {
                int b = serialPort.readSingle();
                if (b == -1) {
                    return;
                }
                b &= 0xFF;
                if (b == 0x03) {
                    fireControlKey(KeyEvent.VK_C, (char) 0x03);
                } else if (b == 0x1A) {
                    fireControlKey(KeyEvent.VK_Z, (char) 0x1A);
                } else {
                    synchronized (queue) {
                        queue.addLast(Byte.valueOf((byte) b));
                        queue.notify();
                    }
                }
            }
        }
    }

    private void fireControlKey(int keyCode, char keyChar) {
        // Host-visible trace of job-control delivery (goes to the UART1
        // log, readable via the KDB drainer). Lets agents distinguish a
        // lost byte (VBox pipe) from a lost event (guest dispatch).
        log.info("serial agent console: control key, code=" + keyCode);
        agentConsole.keyPressed(new KeyboardEvent(KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK,
                keyCode, keyChar));
    }

    /**
     * Take the next queued input byte, blocking until one arrives.
     *
     * @return the next input byte (0-255), or -1 if interrupted.
     */
    private int takeByte() {
        synchronized (queue) {
            while (queue.isEmpty()) {
                try {
                    queue.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return queue.removeFirst().byteValue() & 0xFF;
        }
    }

    @Override
    public int read() throws IOException {
        return takeByte();
    }

    @Override
    public int read(char[] buff, int off, int len) throws IOException {
        if (len == 0) return 0;
        // Read exactly one character to remain highly responsive to the shell reader
        int b = takeByte();
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
        int b = takeByte();
        if (b == -1) return -1;
        target.put((char) b);
        return 1;
    }

    @Override
    public boolean ready() throws IOException {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    @Override
    public void close() throws IOException {
        // No-op for raw port reader
    }
}
