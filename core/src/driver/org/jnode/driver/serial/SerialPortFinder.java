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

package org.jnode.driver.serial;

import org.apache.log4j.Logger;
import org.jnode.driver.Bus;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceException;
import org.jnode.driver.DeviceFinder;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.DriverException;

/**
 * Discovers and registers serial port devices (COM1/COM2).
 *
 * @author mgeisse
 */
public class SerialPortFinder implements DeviceFinder {

    private static final Logger log = Logger.getLogger(SerialPortFinder.class);

    /**
     * @see org.jnode.driver.DeviceFinder#findDevices(DeviceManager, Bus)
     */
    public void findDevices(DeviceManager devMan, Bus bus) throws DeviceException {
        log.debug("Starting serial port drivers");

        tryRegister(devMan, bus, "serial0", 0x3f8);
        tryRegister(devMan, bus, "serial1", 0x2f8);
    }

    /**
     * Try to register a serial port device. Resource conflicts (e.g. I/O ports
     * already claimed by another owner) are handled internally by
     * AbstractDeviceManager.start() and will silently leave the device in a
     * registered-but-not-started state. Only failures from doRegister()
     * (e.g. duplicate device name) reach this catch block.
     */
    private void tryRegister(DeviceManager devMan, Bus bus, String name, int basePort) {
        try {
            Device dev = new Device(bus, name);
            dev.setDriver(new SerialPortDriver(basePort));
            devMan.register(dev);
        } catch (DeviceException ex) {
            log.warn("Could not register " + name + ": " + ex.getMessage());
        } catch (DriverException ex) {
            log.warn("Could not set driver for " + name + ": " + ex.getMessage());
        }
    }
}
