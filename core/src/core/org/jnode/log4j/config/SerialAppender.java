/*
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

package org.jnode.log4j.config;

import java.io.IOException;
import java.io.Writer;

import org.apache.log4j.Layout;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.jnode.driver.DeviceUtils;
import org.jnode.driver.serial.SerialPortAPI;

/**
 * Log4j appender that writes to a serial port via SerialPortDriver's API.
 * Uses lazy initialization - the serial port device may not be available
 * when this appender is created (plugins start before device finders).
 * Pending output is buffered until the device becomes available.
 *
 * @author JNode.org
 */
public class SerialAppender extends WriterAppender {

    private static final Logger log = Logger.getLogger(SerialAppender.class);
    private static final String SERIAL_PORT = "serial0";

    private final Writer writer;

    public SerialAppender(Layout layout) {
        super();
        this.layout = layout;
        this.writer = new SerialWriter();
        super.setWriter(this.writer);
    }

    @Override
    protected void closeWriter() {
        // Intentionally empty: cleanup handled in SerialWriter.close()
    }

    @Override
    public void activateOptions() {
        // Override to prevent WriterAppender.activateOptions() from
        // calling super.setWriter() again.
    }

    @Override
    public synchronized void setWriter(Writer writer) {
        if (writer != this.writer) {
            throw new IllegalArgumentException("cannot change the writer");
        }
        super.setWriter(writer);
    }

    private class SerialWriter extends Writer {

        private static final int MAX_PENDING_BYTES = 8192;

        private SerialPortAPI serialPort;
        // StringBuilder is sufficient here: all access is guarded by
        // the synchronized init() / synchronized bufferPending() methods.
        private StringBuilder pendingOutput;
        private int droppedBytes;

        public SerialWriter() {
        }

        /**
         * Lazily acquire the serial port device and drain any pending output.
         * Synchronized to prevent races between drain and concurrent bufferPending().
         */
        private synchronized boolean init() {
            if (serialPort != null) {
                return true;
            }
            try {
                serialPort = (SerialPortAPI) DeviceUtils.getAPI(SERIAL_PORT, SerialPortAPI.class);
                drainPendingOutput();
            } catch (Exception e) {
                // Device not available yet - will retry on next write
                serialPort = null;
            }
            return serialPort != null;
        }

        private void drainPendingOutput() {
            if (pendingOutput == null || pendingOutput.length() == 0 || serialPort == null) {
                return;
            }
            String data = pendingOutput.toString();
            pendingOutput = null;
            for (int i = 0; i < data.length(); i++) {
                char ch = data.charAt(i);
                try {
                    if (ch == '\n') {
                        serialPort.writeSingle('\r');
                    }
                    serialPort.writeSingle(ch);
                } catch (Exception e) {
                    // Device became unavailable - re-buffer remaining data
                    serialPort = null;
                    pendingOutput = new StringBuilder(data.substring(i));
                    return;
                }
            }
            if (droppedBytes > 0) {
                log.warn("Serial appender: " + droppedBytes +
                    " bytes dropped while device was unavailable");
                droppedBytes = 0;
            }
        }

        /**
         * Close does not flush pending data. This is intentional for a logging
         * appender - close() should not block on a potentially dead serial port.
         */
        @Override
        public void close() throws IOException {
            serialPort = null;
            pendingOutput = null;
            droppedBytes = 0;
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (init()) {
                writeDirect(cbuf, off, len);
            } else {
                bufferPending(cbuf, off, len);
            }
        }

        private void writeDirect(char[] cbuf, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                char ch = cbuf[off + i];
                try {
                    if (ch == '\n') {
                        serialPort.writeSingle('\r');
                    }
                    serialPort.writeSingle(ch);
                } catch (Exception e) {
                    // Device became unavailable - buffer remainder
                    serialPort = null;
                    bufferPending(cbuf, off + i, len - i);
                    return;
                }
            }
        }

        private synchronized void bufferPending(char[] cbuf, int off, int len) {
            if (pendingOutput == null) {
                pendingOutput = new StringBuilder();
            }
            int space = MAX_PENDING_BYTES - pendingOutput.length();
            if (len > space) {
                droppedBytes += (len - space);
                len = space;
            }
            if (len > 0) {
                pendingOutput.append(cbuf, off, len);
            }
        }
    }
}
