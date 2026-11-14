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
import java.util.Arrays;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IDEDiskDriverFuaTest implements IDEConstants {

    private static final int DEVICE_SECTORS = 4096;
    private static final int WRITE_SECTORS = 2;

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
        parent = new Device(parentBus, "ideFuaTestParent");
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
    public void testFuaWriteUsesFuaCommandAndNoCacheFlush() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4062));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(WRITE_SECTORS * SECTOR_SIZE, 11L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaEnablesMultipleModeWhenDeviceReportsNone() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0000, 0x4062));

        assertEquals("driver must issue SET MULTIPLE MODE before any other "
                + "command when the device reports multiple mode disabled",
            Integer.valueOf(CMD_SETMULT), io.getWrittenCommands().get(0));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 12L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaKeepsDeviceMultipleModeWhenReported() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4062));

        assertTrue("device already in multiple mode must not be reconfigured",
            !io.getWrittenCommands().contains(Integer.valueOf(CMD_SETMULT)));
    }

    @Test
    public void testFuaFallsBackToFlushWhenDeviceDoesNotDeclareSupport() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4023));

        assertTrue("SET MULTIPLE MODE must not be issued when the device "
                + "does not declare FUA support",
            !io.getWrittenCommands().contains(Integer.valueOf(CMD_SETMULT)));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 13L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_EXT), Integer.valueOf(CMD_FLUSH_CACHE_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaFallsBackToFlushWhenWord84Invalid() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x0062));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 14L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_EXT), Integer.valueOf(CMD_FLUSH_CACHE_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaFinalStatusErrorSurfacesAsIoException() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4062));
        io.clearObservations();
        io.addPhase(FakeIDEIO.Phase.completed());
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        try {
            driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 21L)));
            fail("final-sector write fault must surface as IOException");
        } catch (IOException expected) {
        }
        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaRequestIssuesSetMultipleModeWhenWriteThroughDisabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0000, 0x4062));

        assertEquals("driver must issue SET MULTIPLE MODE at boot when fua=true, "
                + "independent of writethrough, and the device reports multiple mode disabled",
            Integer.valueOf(CMD_SETMULT), io.getWrittenCommands().get(0));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 16L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaFallsBackToFlushWhenSetMultipleModeRejected() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "true");
        props.setProperty("jnode.ide.fua", "true");
        io.addPhase(FakeIDEIO.Phase.completed());
        io.addPhase(FakeIDEIO.Phase.error(0x24));
        openDriver(props, create48BitDescriptor(0x0000, 0x4062));
        assertEquals(Integer.valueOf(CMD_SETMULT), io.getWrittenCommands().get(0));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 23L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_EXT), Integer.valueOf(CMD_FLUSH_CACHE_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaFallsBackToFlushWhenWriteThroughDisabledAndDeviceDoesNotDeclareSupport()
        throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4023));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 24L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_EXT), Integer.valueOf(CMD_FLUSH_CACHE_EXT)),
            io.getWrittenCommands());
    }

    @Test
    public void testFuaUsedWhenWriteThroughDisabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.fua", "true");
        openDriver(props, create48BitDescriptor(0x0108, 0x4062));
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 15L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT)),
            io.getWrittenCommands());
    }

    private void openDriver(Properties props, IDEDriveDescriptor descr) throws Exception {
        bus = factory.createIDEBus(parent, true);
        final IDEDevice device = new IDEDevice(bus, true, true, "hdFuaTest", descr, null);
        driver = new IDEDiskDriver();
        device.setDriver(driver);
        VmIOContext.setGlobalProperties(props);
        driver.startDevice();
    }

    private static IDEDriveDescriptor create48BitDescriptor(int word59, int word84) {
        final int[] data = new int[256];
        data[60] = (int) (DEVICE_SECTORS & 0xFFFF);
        data[61] = (int) ((DEVICE_SECTORS >> 16) & 0xFFFF);
        data[83] = 0x4000 | 0x0400;
        data[59] = word59;
        data[84] = word84;
        data[100] = (int) (DEVICE_SECTORS & 0xFFFF);
        data[101] = (int) ((DEVICE_SECTORS >> 16) & 0xFFFF);
        data[102] = 0;
        data[103] = 0;
        return new IDEDriveDescriptor(data, false);
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
