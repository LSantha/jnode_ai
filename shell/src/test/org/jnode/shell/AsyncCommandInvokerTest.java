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
 
package org.jnode.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;

import org.jnode.driver.console.TextConsole;
import org.jnode.driver.input.KeyboardEvent;
import org.junit.Test;

public class AsyncCommandInvokerTest {

    private static class TestInvoker extends AsyncCommandInvoker {
        TestInvoker(CommandShell shell) {
            super(shell);
        }

        protected CommandThread createThread(CommandRunner cr) {
            throw new UnsupportedOperationException();
        }

        public String getName() {
            return "test";
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCtrlCReportsStopRequested() {
        CommandThread command = mock(CommandThread.class);
        when(command.isAlive()).thenReturn(true);

        String message = pressCtrlC(command);

        verify(command).stop(any(ThreadDeath.class));
        assertTrue(message.contains("stop requested"));
        assertFalse(message.contains("has been killed"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCtrlCReportsFailedStop() {
        CommandThread command = mock(CommandThread.class);
        when(command.isAlive()).thenReturn(true);
        doThrow(new RuntimeException("cannot stop")).when(command).stop(any(ThreadDeath.class));

        String message = pressCtrlC(command);

        assertTrue(message.contains("could not be stopped"));
        assertFalse(message.contains("has been killed"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCtrlCMessageWrittenOnceWhenConsoleSharesSystemErr() {
        CommandThread command = mock(CommandThread.class);
        when(command.isAlive()).thenReturn(true);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TextConsole console = mock(TextConsole.class);
        CommandShell shell = mock(CommandShell.class);
        when(shell.getConsole()).thenReturn(console);
        when(console.getErr()).thenReturn(new OutputStreamWriter(buffer));

        TestInvoker invoker = new TestInvoker(shell);
        invoker.threadProcess = command;
        invoker.blockingThread = Thread.currentThread();
        invoker.cmdName = "sleep";

        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(buffer));
        try {
            invoker.keyPressed(new KeyboardEvent(KeyEvent.KEY_PRESSED, 0,
                    InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c'));
        } finally {
            System.setErr(oldErr);
            Thread.interrupted();
        }

        String message = buffer.toString();
        int first = message.indexOf("stop requested");
        assertTrue(first >= 0);
        assertEquals(-1, message.indexOf("stop requested", first + 1));
    }

    private String pressCtrlC(CommandThread command) {
        TestInvoker invoker = new TestInvoker(mock(CommandShell.class));
        invoker.threadProcess = command;
        invoker.blockingThread = Thread.currentThread();
        invoker.cmdName = "sleep";

        PrintStream oldErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer));
        try {
            invoker.keyPressed(new KeyboardEvent(KeyEvent.KEY_PRESSED, 0,
                    InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c'));
        } finally {
            System.setErr(oldErr);
            Thread.interrupted();
        }
        return buffer.toString();
    }
}
