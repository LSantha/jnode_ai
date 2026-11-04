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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.jnode.driver.Device;
import org.jnode.driver.character.ChannelAlreadyOwnedException;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.spi.ConsoleWriter;
import org.jnode.driver.input.KeyboardEvent;
import org.jnode.driver.serial.SerialPortAPI;
import org.junit.Test;

public class RawKeyboardReaderTest {

    @Test(timeout = 5000)
    public void testPumpSurvivesOutOfMemoryError() throws Exception {
        final CapturingAppender appender = new CapturingAppender();
        final Logger logger = Logger.getLogger(RawKeyboardReader.class);
        final Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try {
            final ScriptedSerialPort port = new ScriptedSerialPort(new int[] {
                    ScriptedSerialPort.OUT_OF_MEMORY, 'a', 'b', -1
            });
            final TestConsole console = new TestConsole();
            final RawKeyboardReader reader =
                    new RawKeyboardReader(port, console.getConsole());

            assertEquals('a', reader.read());
            assertEquals('b', reader.read());

            assertTrue("the pump failure was not logged at error level",
                    appender.getErrorCount() > 0);
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test(timeout = 5000)
    public void testPumpSurvivesRuntimeException() throws Exception {
        final ScriptedSerialPort port = new ScriptedSerialPort(new int[] {
                ScriptedSerialPort.RUNTIME_FAILURE, 'c', -1
        });
        final TestConsole console = new TestConsole();
        final RawKeyboardReader reader =
                new RawKeyboardReader(port, console.getConsole());

        assertEquals('c', reader.read());
    }

    @Test(timeout = 5000)
    public void testControlKeyDeliveredAfterOutOfMemoryError() throws Exception {
        final CapturingAppender appender = new CapturingAppender();
        final Logger logger = Logger.getLogger(RawKeyboardReader.class);
        final Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try {
            final ScriptedSerialPort port = new ScriptedSerialPort(new int[] {
                    ScriptedSerialPort.OUT_OF_MEMORY, 0x03, -1
            });
            final TestConsole console = new TestConsole();
            new RawKeyboardReader(port, console.getConsole());

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline &&
                    console.getKeyEvents().isEmpty()) {
                Thread.sleep(10);
            }

            assertTrue("the pump failure was not logged at error level",
                    appender.getErrorCount() > 0);
            assertTrue("Ctrl-C was not delivered after the pump failure",
                    console.getKeyEvents().size() > 0);
            assertEquals(KeyEvent.VK_C,
                    console.getKeyEvents().get(0).getKeyCode());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    private static final class CapturingAppender extends AppenderSkeleton {

        private final List<LoggingEvent> events =
                Collections.synchronizedList(new ArrayList<LoggingEvent>());

        protected void append(LoggingEvent event) {
            events.add(event);
        }

        public void close() {
        }

        public boolean requiresLayout() {
            return false;
        }

        int getErrorCount() {
            int count = 0;
            synchronized (events) {
                for (LoggingEvent event : events) {
                    if (event.getLevel().equals(Level.ERROR)) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    private static final class TestConsole implements InvocationHandler {

        private final List<KeyboardEvent> keyEvents =
                Collections.synchronizedList(new ArrayList<KeyboardEvent>());
        private final ConsoleWriter out = new ConsoleWriter(null, 0x07);

        private TestConsole() {
        }

        TextConsole getConsole() {
            return (TextConsole) Proxy.newProxyInstance(
                    RawKeyboardReaderTest.class.getClassLoader(),
                    new Class<?>[] {TextConsole.class}, this);
        }

        List<KeyboardEvent> getKeyEvents() {
            return keyEvents;
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            final String name = method.getName();
            if (name.equals("getOut")) {
                return out;
            }
            if (name.equals("keyPressed")) {
                keyEvents.add((KeyboardEvent) args[0]);
                return null;
            }
            if (name.equals("getWidth") || name.equals("getHeight") ||
                    name.equals("getDeviceWidth") || name.equals("getDeviceHeight")) {
                return Integer.valueOf(80);
            }
            if (name.equals("getTabSize")) {
                return Integer.valueOf(4);
            }
            if (name.equals("isFocused") || name.equals("isCursorVisible")) {
                return Boolean.FALSE;
            }
            if (name.equals("hashCode")) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if (name.equals("equals")) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if (name.equals("toString")) {
                return "RawKeyboardReaderTestConsole";
            }
            final Class<?> type = method.getReturnType();
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == int.class) {
                return Integer.valueOf(0);
            }
            if (type == long.class) {
                return Long.valueOf(0L);
            }
            if (type == char.class) {
                return Character.valueOf('\0');
            }
            if (type == byte.class) {
                return Byte.valueOf((byte) 0);
            }
            if (type == short.class) {
                return Short.valueOf((short) 0);
            }
            if (type == float.class) {
                return Float.valueOf(0f);
            }
            if (type == double.class) {
                return Double.valueOf(0d);
            }
            return null;
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
            return -1;
        }

        public void writeSingle(int value) {
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

    private static final class ScriptedSerialPort extends StubSerialPort {

        static final int OUT_OF_MEMORY = -2;
        static final int RUNTIME_FAILURE = -3;

        private final int[] script;
        private int index;

        ScriptedSerialPort(int[] script) {
            this.script = script;
        }

        public int readSingle() {
            if (index >= script.length) {
                return -1;
            }
            final int value = script[index++];
            if (value == OUT_OF_MEMORY) {
                throw new OutOfMemoryError("injected pump allocation failure");
            }
            if (value == RUNTIME_FAILURE) {
                throw new IllegalStateException("injected pump failure");
            }
            return value;
        }
    }
}
