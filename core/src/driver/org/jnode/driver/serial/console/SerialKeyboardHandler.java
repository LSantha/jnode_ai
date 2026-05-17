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

import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.console.textscreen.KeyboardHandler;
import org.jnode.driver.input.KeyboardEvent;

/**
 * A {@link KeyboardHandler} that reads bytes from a serial port and translates
 * them into {@link KeyboardEvent} objects. Supports VT100/ANSI escape sequences
 * for arrow keys, delete, home, end, etc.
 * <p>
 * This enables a terminal emulator connected via serial port to provide
 * keyboard input to the JNode console system.
 *
 * @author JNode.org
 */
public class SerialKeyboardHandler extends KeyboardHandler {

    private final SerialPortAPI serialPort;
    private final SerialInputReader inputReader;

    /**
     * Construct a serial keyboard handler.
     *
     * @param serialPort the serial port to read input from.
     */
    public SerialKeyboardHandler(SerialPortAPI serialPort) {
        this.serialPort = serialPort;
        this.inputReader = new SerialInputReader();
        this.inputReader.setDaemon(true);
        this.inputReader.start();
    }

    @Override
    public void close() throws IOException {
        inputReader.shutdown();
    }

    /**
     * Background thread that continuously reads bytes from the serial port
     * and converts them into KeyboardEvents.
     */
    private class SerialInputReader extends Thread {
        private volatile boolean running = true;
        private int lastByte = -1;

        public SerialInputReader() {
            super("SerialKeyboardReader");
        }

        public void shutdown() {
            running = false;
            this.interrupt();
        }

        @Override
        public void run() {
            while (running) {
                try {
                    int b = serialPort.readSingle();
                    if (b == -1) break; // Cleanly exit if the driver was interrupted
                    b &= 0xFF;
                    
                    // Suppress LF immediately following CR (avoid double-enter)
                    if (b == 0x0A && lastByte == 0x0D) {
                        lastByte = b;
                        continue;
                    }
                    lastByte = b;
                    processInputByte(b);
                } catch (Exception e) {
                    if (running) {
                        // Log but continue - the serial port may recover
                        System.err.println("SerialKeyboardHandler: read error: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Process a single byte read from the serial port.
     * Handles ordinary characters, control characters, and
     * initiates escape sequence parsing when ESC is received.
     */
    private void processInputByte(int b) {
        long time = System.currentTimeMillis();

        switch (b) {
            case 0x0D: // CR (Enter)
            case 0x0A: // LF (Enter)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_ENTER, '\n'));
                break;

            case 0x08: // Backspace (BS)
            case 0x7F: // DEL (also backspace on many terminals)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_BACK_SPACE, '\b'));
                break;

            case 0x09: // Tab
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_TAB, '\t'));
                break;

            case 0x01: // Ctrl-A (Home)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_A, (char) 0x01));
                break;

            case 0x03: // Ctrl-C (Break)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_C, (char) 0x03));
                break;

            case 0x04: // Ctrl-D (EOF)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_D, (char) 0x04));
                break;

            case 0x05: // Ctrl-E (End)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_E, (char) 0x05));
                break;

            case 0x0C: // Ctrl-L (Clear/Kill line)
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_L, (char) 0x0C));
                break;

            case 0x1B: // ESC - start of escape sequence
                processEscapeSequence();
                break;

            default:
                if (b >= 0x20 && b <= 0x7E) {
                    // Printable ASCII
                    char ch = (char) b;
                    postEvent(new KeyboardEvent(
                        KeyEvent.KEY_PRESSED, time, 0,
                        KeyEvent.VK_UNDEFINED, ch));
                }
                // Ignore other control characters
                break;
        }
    }

    /**
     * Timeout in milliseconds for reading subsequent bytes of an escape sequence.
     * If a byte doesn't arrive within this window after ESC, we treat ESC as standalone.
     * 100ms is well above the inter-byte gap of any real escape sequence (which arrives
     * in microseconds over serial), but short enough that a bare ESC keypress feels instant.
     */
    private static final long ESCAPE_TIMEOUT_MS = 100;

    /**
     * Read a single byte from the serial port with a timeout.
     * Polls {@link SerialPortAPI#isDataAvailable()} until data is ready
     * or the deadline expires, avoiding thread creation overhead.
     *
     * @param timeoutMs maximum time to wait in milliseconds.
     * @return the byte read (0-255), or -1 on timeout.
     */
    private int readWithTimeout(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int spinCount = 0;

        while (!serialPort.isDataAvailable()) {
            if (System.currentTimeMillis() >= deadline) {
                return -1;
            }
            if (++spinCount > 100) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            } else {
                Thread.yield();
            }
        }

        return serialPort.readSingle() & 0xFF;
    }

    /**
     * Parse a VT100/ANSI escape sequence after ESC has been received.
     * Uses timed reads to avoid blocking indefinitely if the terminal
     * sends a bare ESC without a continuation sequence.
     * <p>
     * Supports:
     * <ul>
     * <li>ESC [ A - Up arrow</li>
     * <li>ESC [ B - Down arrow</li>
     * <li>ESC [ C - Right arrow</li>
     * <li>ESC [ D - Left arrow</li>
     * <li>ESC [ H - Home</li>
     * <li>ESC [ F - End</li>
     * <li>ESC [ 3 ~ - Delete</li>
     * <li>ESC [ 1 ~ - Home (alternate)</li>
     * <li>ESC [ 4 ~ - End (alternate)</li>
     * <li>ESC [ 5 ~ - Page Up</li>
     * <li>ESC [ 6 ~ - Page Down</li>
     * <li>ESC [ 1 ; <mod> <key> - Modified arrow keys</li>
     * </ul>
     */
    private void processEscapeSequence() {
        int b2 = readWithTimeout(ESCAPE_TIMEOUT_MS);

        if (b2 == -1) {
            // Timeout - treat as standalone ESC keypress
            long time = System.currentTimeMillis();
            postEvent(new KeyboardEvent(
                KeyEvent.KEY_PRESSED, time, 0,
                KeyEvent.VK_ESCAPE, (char) 0x1B));
            return;
        }

        if (b2 != '[' && b2 != 'O') {
            // Not a CSI or SS3 sequence - treat ESC as standalone
            long time = System.currentTimeMillis();
            postEvent(new KeyboardEvent(
                KeyEvent.KEY_PRESSED, time, 0,
                KeyEvent.VK_ESCAPE, (char) 0x1B));
            
            // Re-inject the intercepted byte as standard input
            processInputByte(b2);
            return;
        }

        // CSI or SS3 sequence: ESC [ or ESC O
        int b3 = readWithTimeout(ESCAPE_TIMEOUT_MS);
        if (b3 == -1) {
            // Incomplete sequence - emit ESC and the opener as separate input
            long time = System.currentTimeMillis();
            postEvent(new KeyboardEvent(
                KeyEvent.KEY_PRESSED, time, 0,
                KeyEvent.VK_ESCAPE, (char) 0x1B));
            processInputByte(b2);
            return;
        }

        long time = System.currentTimeMillis();

        switch (b3) {
            case 'A': // Up arrow
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED));
                break;

            case 'B': // Down arrow
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED));
                break;

            case 'C': // Right arrow
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED));
                break;

            case 'D': // Left arrow
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED));
                break;

            case 'H': // Home
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED));
                break;

            case 'F': // End
                postEvent(new KeyboardEvent(
                    KeyEvent.KEY_PRESSED, time, 0,
                    KeyEvent.VK_END, KeyEvent.CHAR_UNDEFINED));
                break;

            case '1': // ESC [ 1 ~ = Home (alternate) or ESC [ 1 ; 2 A = Shift+Up
                int nextChar = readWithTimeout(ESCAPE_TIMEOUT_MS);
                if (nextChar == -1) {
                    // Incomplete - ignore partial sequence
                    break;
                }
                if (nextChar == ';') {
                    int mod = readWithTimeout(ESCAPE_TIMEOUT_MS);
                    if (mod == -1) break;
                    int modifiers = 0;
                    if (mod == '2') modifiers = InputEvent.SHIFT_DOWN_MASK;
                    else if (mod == '5') modifiers = InputEvent.CTRL_DOWN_MASK;
                    
                    int key = readWithTimeout(ESCAPE_TIMEOUT_MS);
                    if (key == -1) break;
                    int vk = KeyEvent.VK_UNDEFINED;
                    switch (key) {
                        case 'A': vk = KeyEvent.VK_UP; break;
                        case 'B': vk = KeyEvent.VK_DOWN; break;
                        case 'C': vk = KeyEvent.VK_RIGHT; break;
                        case 'D': vk = KeyEvent.VK_LEFT; break;
                    }
                    if (vk != KeyEvent.VK_UNDEFINED) {
                        postEvent(new KeyboardEvent(KeyEvent.KEY_PRESSED, time, modifiers, vk, KeyEvent.CHAR_UNDEFINED));
                    }
                } else if (nextChar == '~') {
                    postEvent(new KeyboardEvent(KeyEvent.KEY_PRESSED, time, 0, KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED));
                } else {
                    // Unexpected byte after '1' - re-inject as standard input
                    processInputByte(nextChar);
                }
                break;

            case '3': // ESC [ 3 ~ = Delete
            case '4': // ESC [ 4 ~ = End (alternate)
            case '5': // ESC [ 5 ~ = Page Up
            case '6': // ESC [ 6 ~ = Page Down
                // Read the trailing '~'
                int tilde = readWithTimeout(ESCAPE_TIMEOUT_MS);
                if (tilde == '~') {
                    switch (b3) {
                        case '3':
                            postEvent(new KeyboardEvent(
                                KeyEvent.KEY_PRESSED, time, 0,
                                KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED));
                            break;
                        case '4':
                            postEvent(new KeyboardEvent(
                                KeyEvent.KEY_PRESSED, time, 0,
                                KeyEvent.VK_END, KeyEvent.CHAR_UNDEFINED));
                            break;
                        case '5':
                            postEvent(new KeyboardEvent(
                                KeyEvent.KEY_PRESSED, time, 0,
                                KeyEvent.VK_PAGE_UP, KeyEvent.CHAR_UNDEFINED));
                            break;
                        case '6':
                            postEvent(new KeyboardEvent(
                                KeyEvent.KEY_PRESSED, time, 0,
                                KeyEvent.VK_PAGE_DOWN, KeyEvent.CHAR_UNDEFINED));
                            break;
                    }
                }
                // else: timeout or unexpected char - drop partial sequence
                break;

            default:
                // Unknown escape sequence - ignore
                break;
        }
    }
}
