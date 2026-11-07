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
import java.util.List;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IDEDiskDriverMultiSectorWriteTest implements IDEConstants {

    private static final int DEVICE_SECTORS = 4096;
    private static final int WRITE_SECTORS = 8;
    private static final long WRITE_LBA = 2;

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
        parent = new Device(parentBus, "ideTestParent");
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
    public void testMultiSectorWriteCompletesWithExpectedTaskfileAndData() throws Exception {
        openDriver(new Properties());
        final byte[] payload = randomBytes(WRITE_SECTORS * SECTOR_SIZE, 613L);
        io.clearObservations();

        final long start = System.currentTimeMillis();
        driver.write(WRITE_LBA * SECTOR_SIZE, ByteBuffer.wrap(payload));
        final long elapsed = System.currentTimeMillis() - start;

        assertTrue("write must finish within the command timeout, took " + elapsed + "ms",
            elapsed < IDE_DATA_XFER_TIMEOUT);

        assertEquals("write must issue one taskfile",
            Arrays.asList(Integer.valueOf(CMD_WRITE), Integer.valueOf(CMD_FLUSH_CACHE)),
            io.getWrittenCommands());
        assertEquals(Integer.valueOf(WRITE_SECTORS), last(io.getWrittenSectorCounts()));
        assertEquals(Integer.valueOf((int) WRITE_LBA), last(io.getWrittenLbaLows()));
        assertEquals(Integer.valueOf(0), last(io.getWrittenLbaMids()));
        assertEquals(Integer.valueOf(0), last(io.getWrittenLbaHighs()));
        assertTrue("write taskfile must select LBA mode on the master drive",
            io.getWrittenSelects().contains(Integer.valueOf(SEL_LBA | SEL_BLANK | SEL_DRIVE_MASTER)));

        assertArrayEquals("device payload must match the source buffer byte for byte",
            payload, wordsToBytes(io.getWrittenWords()));
    }

    @Test
    public void testWriteThroughFlushesAfterWrite() throws Exception {
        openDriver(new Properties());
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 1L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE), Integer.valueOf(CMD_FLUSH_CACHE)),
            io.getWrittenCommands());
    }

    @Test
    public void testWriteThroughDisabledSkipsFlush() throws Exception {
        final Properties props = new Properties();
        props.setProperty("jnode.ide.writethrough", "false");
        openDriver(props);
        io.clearObservations();

        driver.write(0, ByteBuffer.wrap(randomBytes(SECTOR_SIZE, 2L)));

        assertEquals(Arrays.asList(Integer.valueOf(CMD_WRITE)), io.getWrittenCommands());
    }

    @Test
    public void testUnalignedOffsetRejected() throws Exception {
        openDriver(new Properties());
        try {
            driver.write(1L, ByteBuffer.wrap(new byte[SECTOR_SIZE]));
            fail("unaligned offset must be rejected");
        } catch (IOException expected) {
        }
    }

    @Test
    public void testUnalignedLengthRejected() throws Exception {
        openDriver(new Properties());
        try {
            driver.write(0L, ByteBuffer.wrap(new byte[SECTOR_SIZE - 1]));
            fail("unaligned length must be rejected");
        } catch (IOException expected) {
        }
    }

    @Test
    public void testWritePastDeviceEndRejected() throws Exception {
        openDriver(new Properties());
        final long lastSectorOffset = (DEVICE_SECTORS - 1L) * SECTOR_SIZE;
        try {
            driver.write(lastSectorOffset, ByteBuffer.wrap(new byte[2 * SECTOR_SIZE]));
            fail("write past the device end must be rejected");
        } catch (IOException expected) {
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

    private static byte[] randomBytes(int size, long seed) {
        final byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    private static byte[] wordsToBytes(List<Integer> words) {
        final byte[] out = new byte[words.size() * 2];
        for (int i = 0; i < words.size(); i++) {
            final int word = words.get(i).intValue();
            out[i * 2] = (byte) (word & 0xFF);
            out[i * 2 + 1] = (byte) ((word >> 8) & 0xFF);
        }
        return out;
    }

    private static Integer last(List<Integer> values) {
        return values.get(values.size() - 1);
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
