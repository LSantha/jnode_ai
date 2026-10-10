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

package org.jnode.driver.sound.ac97;

import org.jnode.driver.Device;
import org.jnode.driver.Driver;
import org.jnode.driver.DriverException;
import org.jnode.driver.bus.pci.PCIDevice;
import org.jnode.plugin.ConfigurationElement;
import org.jnode.system.resource.ResourceNotFreeException;
import org.jnode.util.TimeoutException;

/**
 * Driver for an Intel AC'97 audio controller (ICH / 82801AA and compatible).
 * <p>
 * The driver is bound to the PCI device through a
 * {@link org.jnode.driver.bus.pci.PCIDeviceToDriverMapper} extension declared
 * in the plugin descriptor:
 *
 * <pre>
 *   &lt;mapper id="8086:2415" name="Intel ICH AC'97"
 *       driver-class="org.jnode.driver.sound.ac97.AC97Driver"
 *       class="org.jnode.driver.bus.pci.PCIDeviceToDriverMapper"/&gt;
 * </pre>
 *
 * The mapper instantiates this class with the {@link ConfigurationElement}
 * of the extension; therefore the driver is optional argument to the
 * constructor. All hardware access is delegated to {@link AC97Core}.
 *
 * @author JNode contributors
 */
public class AC97Driver extends Driver implements AC97API, AC97Constants {

    /**
     * The hardware core; created in {@link #startDevice} and released in
     * {@link #stopDevice}.
     */
    private AC97Core core;

    /**
     * Create a new driver instance for the device that was matched by the
     * mapper.
     *
     * @param config the configuration element of the mapper extension
     */
    public AC97Driver(ConfigurationElement config) {
        // Nothing to configure yet; device ids are matched by the plugin
        // descriptor, not by runtime configuration.
    }

    /**
     * Connect to the device. The PCI device is checked for the expected
     * vendor and device id, so that a generic mapping through the class
     * code (0401h) can never bind this driver to a foreign chip.
     */
    @Override
    protected void verifyConnect(Device device) throws DriverException {
        if (!(device instanceof PCIDevice)) {
            throw new DriverException("AC'97 driver is only usable on PCI devices");
        }
        final PCIDevice pciDev = (PCIDevice) device;
        final int vendor = pciDev.getConfig().getVendorID();
        final int deviceId = pciDev.getConfig().getDeviceID();
        if ((vendor != PCI_VENDOR_ID_INTEL) || (deviceId != PCI_DEVICE_ID_ICH_AC97)) {
            throw new DriverException("Not an Intel ICH AC'97 controller: "
                + Integer.toHexString(vendor) + ':' + Integer.toHexString(deviceId));
        }
    }

    /**
     * Start the device: create the core, which claims the NAM and NABM I/O
     * resources, the interrupt and the DMA memory, and register this driver
     * as the {@link AC97API} implementation of the device.
     */
    @Override
    public void startDevice() throws DriverException {
        final PCIDevice device = (PCIDevice) getDevice();
        try {
            core = new AC97Core(device);
        } catch (ResourceNotFreeException ex) {
            throw new DriverException("Cannot claim AC'97 resources", ex);
        } catch (DriverException ex) {
            throw ex;
        }
        device.registerAPI(AC97API.class, this);
    }

    /**
     * Stop the device: unregister the API and release all hardware resources.
     */
    @Override
    public void stopDevice() throws DriverException {
        getDevice().unregisterAPI(AC97API.class);
        final AC97Core coreSnapshot = core;
        if (coreSnapshot != null) {
            coreSnapshot.release();
        }
        core = null;
    }

    // ------------------------------------------------------------------
    // AC97API implementation
    // ------------------------------------------------------------------

    public void open(int sampleRate) {
        final AC97Core c = core;
        if (c == null) {
            throw new IllegalStateException("AC'97 device is not started");
        }
        c.open(sampleRate);
    }

    public void close() {
        final AC97Core c = core;
        if (c != null) {
            c.close();
        }
    }

    public boolean isOpen() {
        final AC97Core c = core;
        return (c != null) && c.isOpen();
    }

    public boolean isVariableRateSupported() {
        final AC97Core c = core;
        return (c != null) && c.isVariableRateSupported();
    }

    public int getSampleRate() {
        final AC97Core c = core;
        if (c == null) {
            return 0;
        }
        return c.getSampleRate();
    }

    public void setSampleRate(int rate) {
        final AC97Core c = core;
        if (c == null) {
            throw new IllegalStateException("AC'97 device is not started");
        }
        c.setSampleRate(rate);
    }

    public void write(byte[] pcm, int offset, int length)
        throws InterruptedException, TimeoutException {
        final AC97Core c = core;
        if (c == null) {
            throw new IllegalStateException("AC'97 device is not started");
        }
        c.write(pcm, offset, length);
    }

    public int getQueuedFrames() {
        final AC97Core c = core;
        if (c == null) {
            return 0;
        }
        return c.getQueuedFrames();
    }
}
