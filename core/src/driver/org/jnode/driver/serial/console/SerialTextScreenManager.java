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
import org.jnode.driver.textscreen.TextScreenManager;

/**
 * Implementation of {@link TextScreenManager} for serial console.
 * Creates and manages {@link SerialTextScreen} instances backed by a serial port.
 *
 * @author JNode.org
 */
public final class SerialTextScreenManager implements TextScreenManager {

    private SerialPortAPI serialPort;
    private SerialTextScreen systemScreen;

    /**
     * Set the serial port to use for text screen output.
     *
     * @param serialPort the serial port API.
     */
    public void setSerialPort(SerialPortAPI serialPort) {
        this.serialPort = serialPort;
        // Invalidate cached screen when port changes
        this.systemScreen = null;
    }

    /**
     * Get the system screen for this serial port.
     * Creates one if it doesn't exist yet.
     *
     * @return the serial text screen.
     */
    @Override
    public SerialTextScreen getSystemScreen() {
        if (systemScreen == null) {
            if (serialPort == null) {
                throw new IllegalStateException("Serial port not configured");
            }
            systemScreen = new SerialTextScreen(serialPort);
        }
        return systemScreen;
    }
}
