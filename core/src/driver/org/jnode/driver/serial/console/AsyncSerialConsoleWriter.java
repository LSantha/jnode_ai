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

import org.apache.log4j.Logger;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.spi.ConsoleWriter;
import org.jnode.driver.serial.SerialPortAPI;

/**
 * A console writer that hands characters to a dedicated drain thread instead of
 * performing the (potentially unbounded) serial port write on the caller's
 * thread. This keeps a stalled UART from turning every command writer into a
 * long term holder of the shared console writer monitor.
 *
 * @author JNode.org
 */
public class AsyncSerialConsoleWriter extends ConsoleWriter {

    private static final Logger log = Logger.getLogger(AsyncSerialConsoleWriter.class);

    static final int MAX_PENDING_CHARS = 8192;

    private final SerialPortAPI serialPort;
    private final Object lock = new Object();
    private final StringBuilder pending = new StringBuilder();

    private long droppedChars;
    private boolean closed;
    private boolean failed;

    public AsyncSerialConsoleWriter(TextConsole console, int fgColor,
            SerialPortAPI serialPort) {
        super(console, fgColor);
        if (serialPort == null) {
            throw new IllegalArgumentException("null serial port");
        }
        this.serialPort = serialPort;
        Thread drainThread = new Thread(new Runnable() {
            public void run() {
                drainLoop();
            }
        }, "serial-console-output-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    @Override
    public void write(int b) throws IOException {
        write(new char[] {(char) b}, 0, 1);
    }

    @Override
    public void write(char[] cbuf, int off, int len)
        throws IOException, NullPointerException, IndexOutOfBoundsException {
        if (off < 0 || len < 0 || off + len > cbuf.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }
        synchronized (lock) {
            if (failed) {
                droppedChars += len;
                return;
            }
            int space = MAX_PENDING_CHARS - pending.length();
            if (len > space) {
                droppedChars += len - space;
                len = space;
            }
            if (len > 0) {
                pending.append(cbuf, off, len);
            }
            lock.notifyAll();
        }
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private void drainLoop() {
        while (true) {
            String chunk;
            synchronized (lock) {
                while (pending.length() == 0 && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (pending.length() == 0 && closed) {
                    return;
                }
                chunk = pending.toString();
                pending.setLength(0);
            }
            try {
                writeChunk(chunk);
            } catch (RuntimeException ex) {
                synchronized (lock) {
                    failed = true;
                    droppedChars += chunk.length() + pending.length();
                    pending.setLength(0);
                }
                log.error("Serial console output failed; disabling further output", ex);
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private void writeChunk(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (ch == '\n') {
                serialPort.writeSingle('\r');
            }
            serialPort.writeSingle(ch);
        }
    }

    int getPendingCount() {
        synchronized (lock) {
            return pending.length();
        }
    }

    long getDroppedChars() {
        synchronized (lock) {
            return droppedChars;
        }
    }

    boolean isFailed() {
        synchronized (lock) {
            return failed;
        }
    }
}
