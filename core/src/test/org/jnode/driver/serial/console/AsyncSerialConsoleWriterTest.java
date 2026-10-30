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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Arrays;

import org.jnode.driver.Device;
import org.jnode.driver.character.ChannelAlreadyOwnedException;
import org.jnode.driver.serial.SerialPortAPI;
import org.junit.Test;

public class AsyncSerialConsoleWriterTest {

    @Test
    public void testWriteDoesNotBlockOnStalledPort() throws Exception {
        final BlockingSerialPort port = new BlockingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('!');
            port.awaitBlocked(2000);
            char[] data = new char[1024];
            Arrays.fill(data, 'x');

            long start = System.currentTimeMillis();
            writer.write(data, 0, data.length);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue("write() blocked for " + elapsed + "ms on a stalled port",
                    elapsed < 2000);
        } finally {
            port.release();
            writer.close();
        }
    }

    @Test
    public void testQueueIsBoundedAndDropsExcess() throws Exception {
        final BlockingSerialPort port = new BlockingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('!');
            port.awaitBlocked(2000);

            char[] data = new char[AsyncSerialConsoleWriter.MAX_PENDING_CHARS];
            Arrays.fill(data, 'y');
            writer.write(data, 0, data.length);
            writer.write(data, 0, 100);

            assertTrue("pending queue exceeded its bound",
                    writer.getPendingCount() <= AsyncSerialConsoleWriter.MAX_PENDING_CHARS);
            assertTrue("excess output was not dropped", writer.getDroppedChars() > 0);
        } finally {
            port.release();
            writer.close();
        }
    }

    @Test
    public void testOversizedOutputRetainsTruncationMarker() throws Exception {
        final BlockingSerialPort port = new BlockingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('!');
            port.awaitBlocked(2000);

            final int length = AsyncSerialConsoleWriter.MAX_PENDING_CHARS + 1808;
            char[] data = new char[length];
            Arrays.fill(data, 'C');
            writer.write(data, 0, data.length);

            long expectedDropped = length - AsyncSerialConsoleWriter.MAX_PENDING_CHARS;
            port.release();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline &&
                    port.getReceived().indexOf("truncated") < 0) {
                Thread.sleep(10);
            }
            String received = port.getReceived();
            assertTrue("truncation marker missing from output",
                    received.indexOf("[... truncated " + expectedDropped + " chars ...]") >= 0);
            assertTrue("truncation marker was not retained at the tail: " + tail(received),
                    received.endsWith("chars ...]\r\n"));
        } finally {
            port.release();
            writer.close();
        }
    }

    @Test
    public void testSmallOutputIsDeliveredWithoutTruncationMarker() throws Exception {
        final BlockingSerialPort port = new BlockingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('!');
            port.awaitBlocked(2000);

            char[] data = new char[100];
            Arrays.fill(data, 's');
            writer.write(data, 0, data.length);
            port.release();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline &&
                    port.getReceived().length() < data.length + 1) {
                Thread.sleep(10);
            }
            String received = port.getReceived();
            assertTrue("small output was not fully delivered: " + tail(received),
                    received.length() == data.length + 1);
            assertFalse("small output must not carry a truncation marker",
                    received.indexOf("truncated") >= 0);
            assertTrue("small output was unexpectedly dropped",
                    writer.getDroppedChars() == 0);
        } finally {
            port.release();
            writer.close();
        }
    }

    private static String tail(String value) {
        int start = value.length() > 80 ? value.length() - 80 : 0;
        return value.substring(start);
    }

    @Test
    public void testDrainResumesWhenPortRecovers() throws Exception {
        final BlockingSerialPort port = new BlockingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('!');
            port.awaitBlocked(2000);
            char[] data = "hello\nworld".toCharArray();
            writer.write(data, 0, data.length);
            port.release();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline &&
                    !port.getReceived().equals("!hello\r\nworld")) {
                Thread.sleep(10);
            }
            assertTrue("drained output was not delivered: " + port.getReceived(),
                    port.getReceived().equals("!hello\r\nworld"));
        } finally {
            port.release();
            writer.close();
        }
    }

    @Test
    public void testThrowingPortDisablesWriterWithAccounting() throws Exception {
        final ThrowingSerialPort port = new ThrowingSerialPort();
        final AsyncSerialConsoleWriter writer =
                new AsyncSerialConsoleWriter(null, 0x07, port);
        try {
            writer.write('a');
            port.awaitWriteAttempt(2000);

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !writer.isFailed()) {
                Thread.sleep(10);
            }
            assertTrue("writer did not detect the port fault", writer.isFailed());

            long droppedBefore = writer.getDroppedChars();
            writer.write('b');
            assertTrue("writes after failure were not dropped with accounting",
                    writer.getDroppedChars() > droppedBefore);
            assertTrue("failed writer still queued output", writer.getPendingCount() == 0);
        } finally {
            writer.close();
        }
    }

    private abstract static class StubSerialPort implements SerialPortAPI {

        public void configure(int divisor, int bits, boolean longStop,
                boolean parity, boolean pEven) {
        }

        public void configure(int divisor) {
        }

        public boolean isDataAvailable() {
            return true;
        }

        public int readSingle() {
            return 0;
        }

        public void flush() {
        }

        public ByteChannel getChannel(Device owner) throws ChannelAlreadyOwnedException {
            return new ByteChannel() {
                public int read(ByteBuffer dst) {
                    return 0;
                }

                public int write(ByteBuffer src) {
                    return src.remaining();
                }

                public boolean isOpen() {
                    return true;
                }

                public void close() {
                }
            };
        }
    }

    private static final class BlockingSerialPort extends StubSerialPort {

        private final Object monitor = new Object();
        private final StringBuilder received = new StringBuilder();
        private boolean blocked;
        private boolean released;

        public void awaitBlocked(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (monitor) {
                while (!blocked) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    monitor.wait(remaining);
                }
                assertTrue("drain thread never touched the port", blocked);
            }
        }

        public void release() {
            synchronized (monitor) {
                released = true;
                monitor.notifyAll();
            }
        }

        public String getReceived() {
            synchronized (received) {
                return received.toString();
            }
        }

        public void writeSingle(int value) {
            synchronized (monitor) {
                blocked = true;
                monitor.notifyAll();
                while (!released) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            synchronized (received) {
                received.append((char) (value & 0xFF));
            }
        }
    }

    private static final class ThrowingSerialPort extends StubSerialPort {

        private final Object monitor = new Object();
        private boolean attempted;

        public void awaitWriteAttempt(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (monitor) {
                while (!attempted) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    monitor.wait(remaining);
                }
                assertTrue("drain thread never touched the port", attempted);
            }
        }

        public void writeSingle(int value) {
            synchronized (monitor) {
                attempted = true;
                monitor.notifyAll();
            }
            throw new IllegalStateException("injected serial port fault");
        }
    }
}
