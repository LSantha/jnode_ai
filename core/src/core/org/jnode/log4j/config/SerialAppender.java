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
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

import javax.naming.NameNotFoundException;

import org.apache.log4j.Layout;
import org.apache.log4j.WriterAppender;
import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.IOResource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.system.resource.SimpleResourceOwner;
import org.jnode.vm.Unsafe;

/**
 * Log4j appender that writes directly to a serial port (COM1/COM2)
 * via IOResource, bypassing Unsafe.debug() to avoid VGA screen output.
 *
 * @author JNode.org
 */
public class SerialAppender extends WriterAppender {

    private static final int COM1_BASE = 0x3F8;
    private static final int COM1_LENGTH = 8;

    private IOResource ioResource;
    private final Writer writer;

    public SerialAppender(Layout layout) throws Exception {
        super();
        this.layout = layout;

        final ResourceManager rm;
        try {
            rm = InitialNaming.lookup(ResourceManager.NAME);
        } catch (NameNotFoundException e) {
            throw new Exception("ResourceManager not found", e);
        }

        try {
            ioResource = AccessController.doPrivileged(
                new PrivilegedExceptionAction<IOResource>() {
                    public IOResource run() throws Exception {
                        return rm.claimIOResource(
                            new SimpleResourceOwner("Log4j-Serial"), COM1_BASE, COM1_LENGTH);
                    }
                });
        } catch (Exception e) {
            throw new Exception("Cannot claim COM1 ports 0x" +
                Integer.toHexString(COM1_BASE) + "-0x" +
                Integer.toHexString(COM1_BASE + COM1_LENGTH - 1) + ": " + e, e);
        }

        configureUART();

        this.writer = new SerialWriter();
        super.setWriter(this.writer);
    }

    private void configureUART() {
        final int base = COM1_BASE;

        ioResource.outPortByte(base + 3, (byte) 0x80);
        ioResource.outPortByte(base + 0, (byte) 0x0C);
        ioResource.outPortByte(base + 1, (byte) 0x00);
        ioResource.outPortByte(base + 3, (byte) 0x03);
        ioResource.outPortByte(base + 2, (byte) 0xC7);
        ioResource.outPortByte(base + 4, (byte) 0x03);
    }

    @Override
    protected void closeWriter() {
        // Intentionally empty: do NOT close the serial port writer.
        // WriterAppender.reset() calls closeWriter() which would release
        // our IOResource. We handle cleanup in SerialWriter.close() directly.
    }

    @Override
    public void activateOptions() {
        // Writer already set in constructor via super.setWriter().
        // Override to prevent WriterAppender.activateOptions() from
        // calling super.setWriter() again, which would trigger reset().
    }

    @Override
    public synchronized void setWriter(Writer writer) {
        if (writer != this.writer) {
            throw new IllegalArgumentException("cannot change the writer");
        }
        super.setWriter(writer);
    }

    private class SerialWriter extends Writer {

        public SerialWriter() {
        }

        @Override
        public void close() throws IOException {
            if (ioResource != null) {
                ioResource.release();
                ioResource = null;
            }
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (ioResource == null) {
                return;
            }
            final int base = COM1_BASE;
            for (int i = 0; i < len; i++) {
                char ch = cbuf[off + i];
                if (ch == '\n') {
                    writeChar(base, '\r');
                }
                writeChar(base, ch);
            }
        }

        private void writeChar(int base, char ch) throws IOException {
            int timeout = 10000;
            while ((ioResource.inPortByte(base + 5) & 0x20) == 0) {
                if (--timeout <= 0) {
                    return;
                }
            }
            ioResource.outPortByte(base, (byte) ch);
        }
    }
}
