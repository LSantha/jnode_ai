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

import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.textscreen.TextScreen;
import org.jnode.driver.textscreen.x86.AbstractPcTextScreen;

/**
 * A TextScreen implementation that renders output to a serial port using
 * VT100/ANSI escape sequences. This allows a terminal emulator connected
 * to the serial port to display JNode console output.
 *
 * @author JNode.org
 */
public class SerialTextScreen extends AbstractPcTextScreen {

    private final SerialPortAPI serialPort;
    private final char[] buffer;
    private int cursorOffset;

    /** Current terminal cursor position (0-based) */
    private int terminalX = -1;
    private int terminalY = -1;

    /**
     * Default terminal dimensions.
     */
    public static final int DEFAULT_WIDTH = 80;
    public static final int DEFAULT_HEIGHT = 25;

    public SerialTextScreen(SerialPortAPI serialPort) {
        this(serialPort, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public SerialTextScreen(SerialPortAPI serialPort, int width, int height) {
        super(width, height);
        this.serialPort = serialPort;
        this.buffer = new char[width * height];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = ' ';
        }
    }

    @Override
    public void copyFrom(char[] rawData, int rawDataOffset) {
        final int width = getWidth();
        final int height = getHeight();

        // 1-Line Scroll Up Heuristic:
        // Commonly triggered when the screen is full. If the incoming buffer is simply our current buffer shifted up by 1 line,
        // we can take advantage of the terminal emulator's native scrolling instead of diffing and sending 1920 cursor jumps.
        boolean isScrollUp = true;
        for (int i = 0; i < width * (height - 1); i++) {
            if (toDisplayChar(rawData[rawDataOffset + i]) != buffer[width + i]) {
                isScrollUp = false;
                break;
            }
        }
        
        if (isScrollUp) {
            // Emit a hard VT100 scroll
            moveTo(0, height - 1);
            serialPort.writeSingle('\n');
            terminalX = 0; // LF leaves cursor in the same column (0)
            
            // Reconcile our local buffer with the terminal's visible state
            System.arraycopy(buffer, width, buffer, 0, width * (height - 1));
            // Blank the bottom line in our buffer. The diff algorithm below will cleanly populate it with the incoming data.
            for (int i = width * (height - 1); i < buffer.length; i++) {
                buffer[i] = ' ';
            }
        } else {
            // 1-Line Scroll Down Heuristic:
            // Triggered when scrolling up in history via Shift-Up (text moves DOWN).
            boolean isScrollDown = true;
            for (int i = 0; i < width * (height - 1); i++) {
                if (toDisplayChar(rawData[rawDataOffset + width + i]) != buffer[i]) {
                    isScrollDown = false;
                    break;
                }
            }
            if (isScrollDown) {
                // Emit VT100 Reverse Index to scroll terminal text DOWN natively (moves cursor up 1, scrolls if at top)
                moveTo(0, 0);
                serialPort.writeSingle((int) '\033');
                serialPort.writeSingle((int) 'M');
                terminalX = 0;
                
                // Reconcile our local buffer by shifting it down to match the physical terminal state
                System.arraycopy(buffer, 0, buffer, width, width * (height - 1));
                // Blank the top line in our buffer so diff algorithm evaluates it
                for (int i = 0; i < width; i++) {
                    buffer[i] = ' ';
                }
            }
        }
        
        int startDiff = -1;
        int gapTracker = 0;

        for (int i = 0; i < buffer.length; i++) {
            char newChar = toDisplayChar(rawData[rawDataOffset + i]);
            if (buffer[i] != newChar) {
                buffer[i] = newChar;
                if (startDiff == -1) {
                    startDiff = i;
                }
                gapTracker = 0;
            } else {
                if (startDiff != -1) {
                    gapTracker++;
                    // VT100 cursor reposition costs ~6-8 bytes. 
                    // Flush if gap is bigger than the cost to reposition, OR if we hit a line boundary to prevent wrapping chunks.
                    if (gapTracker >= 6 || ((i + 1) % getWidth() == 0)) {
                        int length = (i - gapTracker + 1) - startDiff;
                        sync(startDiff, length);
                        startDiff = -1;
                    }
                }
            }
        }
        
        if (startDiff != -1) {
            int length = buffer.length - gapTracker - startDiff;
            if (length > 0) {
                sync(startDiff, length);
            }
        }
    }

    @Override
    public void copyContent(int srcOffset, int destOffset, int length) {
        System.arraycopy(buffer, srcOffset, buffer, destOffset, length);
        
        final int width = getWidth();
        final int height = getHeight();
        // Optimize 1-line scroll up!
        if (destOffset == 0 && srcOffset == width && length == (height - 1) * width) {
            // Move cursor to bottom left
            moveTo(0, height - 1);
            // Output newline to scroll
            serialPort.writeSingle('\n');
            terminalX = 0;
            // The cursor remains at the bottom left after a newline scroll
            return;
        }

        sync(destOffset, length);
    }

    @Override
    public void copyTo(TextScreen dst, int offset, int length) {
        if (dst instanceof SerialTextScreen) {
            SerialTextScreen dstScreen = (SerialTextScreen) dst;
            System.arraycopy(this.buffer, offset, dstScreen.buffer, offset, length);
        }
    }

    @Override
    public char getChar(int offset) {
        return buffer[offset];
    }

    @Override
    public int getColor(int offset) {
        return 0x07;
    }

    @Override
    public void set(int offset, char ch, int count, int color) {
        char c = toDisplayChar(ch);
        for (int i = 0; i < count && (offset + i) < buffer.length; i++) {
            buffer[offset + i] = c;
        }

        final int width = getWidth();
        if (c == ' ' && offset == 0 && count == buffer.length) {
            // Full clear screen optimize
            writeEscape("[2J");
            writeEscape("[H"); // home
            terminalX = 0;
            terminalY = 0;
            return;
        }
        if (c == ' ') {
            int startX = offset % width;
            if ((startX + count) == width) {
                // Clear to end of line optimize
                int y = offset / width;
                moveTo(startX, y);
                writeEscape("[K");
                return;
            }
        }
        
        sync(offset, count);
    }

    @Override
    public void set(int offset, char[] ch, int chOfs, int length, int color) {
        for (int i = 0; i < length && (offset + i) < buffer.length; i++) {
            buffer[offset + i] = toDisplayChar(ch[chOfs + i]);
        }
        sync(offset, length);
    }

    @Override
    public void set(int offset, char[] ch, int chOfs, int length, int[] colors, int colorsOfs) {
        set(offset, ch, chOfs, length, 0);
    }

    @Override
    public int setCursor(int x, int y) {
        cursorOffset = getOffset(x, y);
        moveTo(x, y);
        return cursorOffset;
    }

    @Override
    public int setCursorVisible(boolean visible) {
        if (visible) writeEscape("[?25h");
        else writeEscape("[?25l");
        return cursorOffset;
    }

    private void moveTo(int x, int y) {
        if (x != terminalX || y != terminalY) {
            writeEscape("[" + (y + 1) + ";" + (x + 1) + "H");
            terminalX = x;
            terminalY = y;
        }
    }

    @Override
    public void sync(int offset, int length) {
        if (length <= 0) return;
        if (offset < 0) offset = 0;
        if (offset + length > buffer.length) length = buffer.length - offset;
        if (length <= 0) return;

        final int width = getWidth();
        int y = offset / width;
        int x = offset % width;

        moveTo(x, y);

        int pos = offset;
        for (int i = 0; i < length && pos < buffer.length; i++) {
            char c = buffer[pos++];
            serialPort.writeSingle(c);

            x++;
            terminalX++;
            
            if (x >= width) {
                x = 0;
                y++;
                // Since terminal auto-wrap is disabled, the physical cursor is stuck at the end of the line!
                // We invalidate our tracking state so the next operation forces a VT100 absolute jump.
                terminalX = -1;
                terminalY = -1;
                
                // If there are more chars in this sync run, reposition now
                if (i + 1 < length && y < getHeight()) {
                    moveTo(x, y);
                }
            }
        }
    }

    private void writeEscape(String seq) {
        serialPort.writeSingle(0x1B);
        for (int i = 0; i < seq.length(); i++) {
            serialPort.writeSingle(seq.charAt(i));
        }
    }

    private char toDisplayChar(char ch) {
        char c = (char) (ch & 0xFF);
        return (c == 0) ? ' ' : c;
    }
}
