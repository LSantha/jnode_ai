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
import java.io.Writer;
import org.jnode.driver.console.textscreen.ConsoleKeyEventBindings;
import org.jnode.driver.console.textscreen.KeyboardReader;
import org.jnode.driver.console.spi.ConsoleWriter;
import org.jnode.driver.console.InputCompleter;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.spi.AbstractConsole;
import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.textscreen.TextScreen;
import org.jnode.driver.console.ConsoleManager;
import org.jnode.driver.input.PointerEvent;
import org.jnode.driver.input.KeyboardEvent;

/**
 * A lightweight, raw TextConsole implementation for agent-based interaction over serial.
 * This console satisfy CommandShell's type requirements by returning KeyboardReader
 * and ConsoleWriter while staying raw and non-visual.
 *
 * @author JNode.org
 */
public class SerialAgentConsole extends AbstractConsole implements TextConsole {

    private final SerialPortAPI serialPort;
    private final KeyboardReader in;
    private final ConsoleWriter out;
    private final ConsoleWriter err;

    public SerialAgentConsole(ConsoleManager mgr, String name, SerialPortAPI serialPort) {
        super(mgr, name);
        this.serialPort = serialPort;
        
        // Use the raw keyboard reader to avoid event queue overhead
        this.in = new RawKeyboardReader(serialPort, this);
        
        // Disable echo in the reader for agent mode (though RawKeyboardReader ignores this anyway)
        this.in.setEcho(false);
        
        // Use standard ConsoleWriter which satisfies CommandShell cast
        this.out = new ConsoleWriter(this, 0x07);
        this.err = this.out;
    }

    @Override
    public void putChar(char v, int color) {
        if (v == '\n') {
            serialPort.writeSingle('\r');
        }
        serialPort.writeSingle(v);
    }

    @Override
    public void putChar(char[] v, int offset, int length, int color) {
        for (int i = 0; i < length; i++) {
            char c = v[offset + i];
            if (c == '\n') {
                serialPort.writeSingle('\r');
            }
            serialPort.writeSingle(c);
        }
    }

    @Override
    public Reader getIn() {
        return in;
    }

    @Override
    public Writer getOut() {
        return out;
    }

    @Override
    public Writer getErr() {
        return err;
    }

    // --- TextConsole interface implementation (mostly NOPs) ---

    @Override public void setCursor(int x, int y) {}
    @Override public int getCursorX() { return 0; }
    @Override public int getCursorY() { return 0; }
    @Override public void setChar(int x, int y, char ch, int color) {}
    @Override public void setChar(int x, int y, char[] cbuf, int color) {}
    @Override public void setChar(int x, int y, char[] cbuf, int offset, int length, int color) {}
    @Override public char getChar(int x, int y) { return ' '; }
    @Override public int getColor(int x, int y) { return 0; }
    @Override public int getWidth() { return 80; }
    @Override public int getHeight() { return 25; }
    @Override public int getDeviceWidth() { return 80; }
    @Override public int getDeviceHeight() { return 25; }
    @Override public void clear() {}
    @Override public void clearRow(int row) {}
    @Override public int getTabSize() { return 4; }
    @Override public void setTabSize(int tabSize) {}
    @Override public boolean isCursorVisible() { return false; }
    @Override public void setCursorVisible(boolean visible) {}
    
    @Override 
    public InputCompleter getCompleter() { 
        return in.getCompleter(); 
    }
    
    @Override 
    public void setCompleter(InputCompleter completer) { 
        in.setCompleter(completer); 
    }
    
    @Override 
    public ConsoleKeyEventBindings getKeyEventBindings() { 
        return in.getKeyEventBindings(); 
    }
    
    @Override 
    public void setKeyEventBindings(ConsoleKeyEventBindings bindings) {
        in.setKeyEventBindings(bindings);
    }

    @Override
    public void systemScreenChanged(TextScreen textScreen) {
        // No-op for agent mode as there is no visual screen to sync
    }

    @Override public void focusGained(org.jnode.system.event.FocusEvent event) {}
    @Override public void focusLost(org.jnode.system.event.FocusEvent event) {}
    @Override public void keyPressed(KeyboardEvent event) {}
    @Override public void keyReleased(KeyboardEvent event) {}
    @Override public void pointerStateChanged(PointerEvent event) {}
}
