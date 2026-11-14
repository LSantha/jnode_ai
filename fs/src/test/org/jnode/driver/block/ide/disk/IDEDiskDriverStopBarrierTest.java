/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.driver.block.ide.disk;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.naming.NameNotFoundException;
import org.jnode.driver.Bus;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.bus.ide.DefaultIDEDeviceFactory;
import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.driver.bus.ide.IDEDevice;
import org.jnode.driver.bus.ide.IDEDeviceFactory;
import org.jnode.driver.bus.ide.IDEDriveDescriptor;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.driver.bus.ide.command.FakeIDEIO;
import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.IRQResource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.test.support.ContextManager;
import org.jnode.vm.VmIOContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IDEDiskDriverStopBarrierTest implements IDEConstants {

    private static final int DEVICE_SECTORS = 4096;

    private FakeIDEIO io;
    private FakeIDEDeviceFactory factory;
    private IDEDeviceFactory originalFactory;
    private DeviceManager originalDeviceManager;
    private Bus parentBus;
    private Device parent;
    private IDEBus bus;
    private IDEDiskDriver driver;

    @Before
    public void setUp() throws Exception {
        ContextManager.getInstance().init();
        io = new FakeIDEIO();
        factory = new FakeIDEDeviceFactory(io);
        originalFactory = InitialNaming.lookup(IDEDeviceFactory.NAME);
        InitialNaming.unbind(IDEDeviceFactory.NAME);
        InitialNaming.bind(IDEDeviceFactory.NAME, factory);
        try {
            originalDeviceManager = InitialNaming.lookup(DeviceManager.NAME);
        } catch (NameNotFoundException e) {
            originalDeviceManager = null;
        }
        InitialNaming.unbind(DeviceManager.NAME);
        InitialNaming.bind(DeviceManager.NAME, emptyDeviceManager());
        bindResourceManager();
        parentBus = new Bus((Bus) null) {
        };
        parent = new Device(parentBus, "ideStopTestParent");
    }

    @After
    public void tearDown() throws Exception {
        if (bus != null) {
            bus.stop();
        }
        VmIOContext.setGlobalProperties(null);
        InitialNaming.unbind(IDEDeviceFactory.NAME);
        if (originalFactory != null) {
            InitialNaming.bind(IDEDeviceFactory.NAME, originalFactory);
        }
        InitialNaming.unbind(DeviceManager.NAME);
        if (originalDeviceManager != null) {
            InitialNaming.bind(DeviceManager.NAME, originalDeviceManager);
        }
        InitialNaming.unbind(ResourceManager.NAME);
    }

    @Test
    public void testStopDeviceFlushesDevice() throws Exception {
        openDriver(new Properties());
        io.clearObservations();

        driver.stopDevice();

        List<Integer> written = io.getWrittenCommands();
        assertTrue("stopDevice must end in a FLUSH CACHE barrier, got " + written,
            written.contains(Integer.valueOf(CMD_FLUSH_CACHE)));
        assertEquals("FLUSH CACHE must be the barrier issued during stop",
            Integer.valueOf(CMD_FLUSH_CACHE), written.get(written.size() - 1));
    }

    @Test
    public void testStopDeviceFlushesDeviceWithWriteThroughEnabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        openDriver(props);
        io.clearObservations();

        driver.stopDevice();

        assertTrue("stopDevice must end in a FLUSH CACHE barrier, got " + io.getWrittenCommands(),
            io.getWrittenCommands().contains(Integer.valueOf(CMD_FLUSH_CACHE)));
    }

    private void openDriver(Properties props) throws Exception {
        bus = factory.createIDEBus(parent, true);
        final IDEDevice device = new IDEDevice(bus, true, true, "hdStopTest",
            createDescriptor(DEVICE_SECTORS), null);
        driver = new IDEDiskDriver();
        device.setDriver(driver);
        VmIOContext.setGlobalProperties(props);
        io.addPhase(FakeIDEIO.Phase.dataIn(new byte[SECTOR_SIZE]));
        driver.startDevice();
    }

    private static IDEDriveDescriptor createDescriptor(long sectors) {
        final int[] data = new int[256];
        data[60] = (int) (sectors & 0xFFFF);
        data[61] = (int) ((sectors >> 16) & 0xFFFF);
        return new IDEDriveDescriptor(data, false);
    }

    private static DeviceManager emptyDeviceManager() {
        return (DeviceManager) Proxy.newProxyInstance(
            DeviceManager.class.getClassLoader(),
            new Class[]{DeviceManager.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getDevices".equals(method.getName())
                        || "getDevicesByAPI".equals(method.getName())) {
                        return Collections.<Device>emptyList();
                    }
                    return defaultValue(method);
                }
            });
    }

    private void bindResourceManager() throws Exception {
        final IRQResource irq = (IRQResource) Proxy.newProxyInstance(
            IRQResource.class.getClassLoader(),
            new Class[]{IRQResource.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getIRQ".equals(method.getName())) {
                        return Integer.valueOf(IDE0_IRQ);
                    }
                    if ("isShared".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    return defaultValue(method);
                }
            });
        final ResourceManager rm = (ResourceManager) Proxy.newProxyInstance(
            ResourceManager.class.getClassLoader(),
            new Class[]{ResourceManager.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("claimIRQ".equals(method.getName())) {
                        return irq;
                    }
                    return defaultValue(method);
                }
            });
        InitialNaming.unbind(ResourceManager.NAME);
        InitialNaming.bind(ResourceManager.NAME, rm);
    }

    private static Object defaultValue(Method method) {
        final Class<?> type = method.getReturnType();
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0);
        }
        return null;
    }

    private static final class FakeIDEDeviceFactory extends DefaultIDEDeviceFactory {
        private final FakeIDEIO io;

        FakeIDEDeviceFactory(FakeIDEIO io) {
            this.io = io;
        }

        public IDEIO createIDEIO(Device parent, boolean primary) {
            return io;
        }
    }
}
