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

package org.jnode.shell.command.driver.console;

import org.apache.log4j.Logger;
import org.jnode.driver.DeviceUtils;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.serial.SerialPortAPI;
import org.jnode.driver.serial.console.SerialAgentConsole;
import org.jnode.driver.serial.console.SerialConsoleManager;
import org.jnode.plugin.Plugin;
import org.jnode.plugin.PluginDescriptor;
import org.jnode.plugin.PluginException;
import org.jnode.shell.CommandShell;
import org.jnode.shell.ShellException;

/**
 * Plugin that automatically starts a serial console on serial1 (COM2)
 * in agent mode when the plugin is loaded.
 *
 * @author Levente Santha
 */
public class SerialConsolePlugin extends Plugin {

    private static final Logger log = Logger.getLogger(SerialConsolePlugin.class);
    private static final String DEFAULT_PORT = "serial1";
    private static final int DEFAULT_BAUD_DIVISOR = 1; // 115200 baud

    private SerialConsoleManager consoleManager;
    private TextConsole console;
    private Thread consoleThread;

    public SerialConsolePlugin(PluginDescriptor descriptor) {
        super(descriptor);
    }

    @Override
    protected void startPlugin() throws PluginException {
        SerialPortAPI serialPort;
        try {
            serialPort = DeviceUtils.getAPI(DEFAULT_PORT, SerialPortAPI.class);
        } catch (Exception e) {
            throw new PluginException("Cannot access serial port '" + DEFAULT_PORT + "': " + e.toString(), e);
        }

        serialPort.configure(DEFAULT_BAUD_DIVISOR);

        try {
            consoleManager = new SerialConsoleManager();
        } catch (Exception e) {
            throw new PluginException("Failed to create serial console manager", e);
        }

        consoleManager.setSerialPort(serialPort);
        console = new SerialAgentConsole(consoleManager, "agent-" + DEFAULT_PORT, serialPort);

        try {
            CommandShell commandShell = new CommandShell(console);
            commandShell.configureShell();
            commandShell.setProperty("jnode.prompt", "\n[JNODE_AGENT_READY]\n");
            consoleThread = new Thread(commandShell, "serial-console");
            consoleThread.setDaemon(true);
            consoleThread.start();
            log.info("Serial console available on " + DEFAULT_PORT + " at 115200 baud");
        } catch (ShellException e) {
            throw new PluginException("Failed to start serial command shell", e);
        }
    }

    @Override
    protected void stopPlugin() throws PluginException {
        if (consoleThread != null) {
            consoleThread.interrupt();
            try {
                consoleThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consoleThread = null;
        }

        if (console != null) {
            console.close();
            console = null;
        }

        if (consoleManager != null) {
            consoleManager = null;
        }
    }
}
