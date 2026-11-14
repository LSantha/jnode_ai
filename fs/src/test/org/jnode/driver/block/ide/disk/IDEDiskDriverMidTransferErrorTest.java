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

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.Random;
import org.jnode.driver.Bus;
import org.jnode.driver.Device;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a mid-transfer sector failure through the full driver path:
 * the resulting IOException must name the failing device sector and the
 * device's own error register value, not a generic late abort.
 */
public class IDEDiskDriverMidTransferErrorTest implements IDEConstants {

    private static final int DEVICE_SECTORS = 4096;

    private FakeIDEIO io;
    private FakeIDEDeviceFactory factory;
    private IDEDeviceFactory originalFactory;
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
        bindResourceManager();
        parentBus = new Bus((Bus) null) {
        };
        parent = new Device(parentBus, "ideMidErrorTestParent");
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
        InitialNaming.unbind(ResourceManager.NAME);
    }

    @Test
    public void testMidTransferFailureNamesSectorAndDeviceErrorCode() throws Exception {
        openDriver(new Properties());
        io.clearObservations();

        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 99L);
        io.addPhase(FakeIDEIO.Phase.dataIn(copyRange(payload, 0, SECTOR_SIZE)));
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        try {
            driver.read(0, ByteBuffer.wrap(new byte[2 * SECTOR_SIZE]));
            fail("mid-transfer failure must surface as IOException");
        } catch (IOException expected) {
            final String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("exception names the device error register value: " + msg,
                msg.indexOf("36") >= 0);
            assertTrue("exception names the failing sector: " + msg,
                msg.indexOf("lba 1") >= 0);
        }
    }

    @Test
    public void testWriteMidTransferFailureNamesSectorAndDeviceErrorCode() throws Exception {
        openDriver(new Properties());
        io.clearObservations();

        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 98L);
        try {
            driver.write(0, ByteBuffer.wrap(payload));
            fail("mid-transfer failure must surface as IOException");
        } catch (IOException expected) {
            final String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("exception names the device error register value: " + msg,
                msg.indexOf("36") >= 0);
            assertTrue("exception names the failing sector: " + msg,
                msg.indexOf("lba 1") >= 0);
        }
    }

    private void openDriver(Properties props) throws Exception {
        bus = factory.createIDEBus(parent, true);
        final IDEDevice device = new IDEDevice(bus, true, true, "hdTest",
            createDescriptor(DEVICE_SECTORS), null);
        driver = new IDEDiskDriver();
        device.setDriver(driver);
        VmIOContext.setGlobalProperties(props);
        driver.startDevice();
    }

    private static IDEDriveDescriptor createDescriptor(long sectors) {
        final int[] data = new int[256];
        data[60] = (int) (sectors & 0xFFFF);
        data[61] = (int) ((sectors >> 16) & 0xFFFF);
        return new IDEDriveDescriptor(data, false);
    }

    private static byte[] copyRange(byte[] src, int ofs, int len) {
        final byte[] out = new byte[len];
        System.arraycopy(src, ofs, out, 0, len);
        return out;
    }

    private static byte[] randomBytes(int size, long seed) {
        final byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
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
