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
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.test.fs.earlymount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.jnode.driver.Device;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.DeviceNotFoundException;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.earlymount.EarlyMountPlugin;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.EarlyMountClaims;
import org.jnode.naming.InitialNaming;
import org.jnode.plugin.PluginDescriptor;
import org.jnode.plugin.PluginException;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EarlyMountPlugin}: the device lookup with the bounded
 * wait, the claim hand-over with the FileSystemMounter, and the mount
 * behaviour at the configured mount point.
 *
 * <p>
 * The FileSystemService is mocked: the real {@code FileSystemPlugin.mount}
 * normalizes paths through {@code java.io.VMFile}, which cannot be loaded
 * from the application classloader on a host JVM ("prohibited package").
 * The plugin logic under test (claim, wait, type selection, mount
 * invocation, legacy replacement) does not depend on the VFS, and the
 * end-to-end mount itself is covered by the boot validation and
 * {@code FileSystemAPIImplTest}.
 */
public class EarlyMountPluginTest {

    private static final String DEVICE_ID = "hda0";

    private FileSystemService fss;
    private DeviceManager dm;
    private FileDevice device;
    private File tempImage;

    @Before
    public void setUp() throws Exception {
        resetDeviceUtilsCache();
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
            // ignore
        }

        fss = mock(FileSystemService.class);
        when(fss.getFileSystem(any(Device.class))).thenReturn(null);
        when(fss.getFileSystemType(FatFileSystemType.class)).thenReturn(new FatFileSystemType());
        when(fss.getDeviceMountPoints()).thenReturn(new HashMap<String, String>());
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        dm = mock(DeviceManager.class);
        try {
            InitialNaming.bind(DeviceManager.NAME, dm);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(DeviceManager.NAME);
            InitialNaming.bind(DeviceManager.NAME, dm);
        }

        // default configuration for the tests; a one-second timeout keeps
        // the missing-device cases fast
        System.setProperty("jnode.earlymount.device", DEVICE_ID);
        System.setProperty("jnode.earlymount.mountpoint", "/data");
        System.setProperty("jnode.earlymount.timeout", "1");
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty("jnode.earlymount.device");
        System.clearProperty("jnode.earlymount.mountpoint");
        System.clearProperty("jnode.earlymount.readonly");
        System.clearProperty("jnode.earlymount.required");
        System.clearProperty("jnode.earlymount.timeout");
        System.clearProperty("jnode.cmdline");
        EarlyMountClaims.release(DEVICE_ID);
        if (device != null) {
            device.close();
            device = null;
        }
        if (tempImage != null) {
            tempImage.delete();
            tempImage = null;
        }
        resetDeviceUtilsCache();
    }

    /**
     * DeviceUtils caches the looked-up DeviceManager in a static field, so a
     * mock bound in a later test would be ignored. Clear the cache so every
     * test picks up its own fresh mock.
     */
    private static void resetDeviceUtilsCache() throws Exception {
        final java.lang.reflect.Field field =
            org.jnode.driver.DeviceUtils.class.getDeclaredField("deviceManager");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void testMountsAtConfiguredMountPoint() throws Exception {
        copyFixture("test/fs/jfat/test.fat16", "jfat-earlymount");
        when(dm.getDevice(DEVICE_ID)).thenReturn(device);

        newPlugin().start();

        final ArgumentCaptor<FileSystem> captor = ArgumentCaptor.forClass(FileSystem.class);
        verify(fss).mount(eq("/data"), captor.capture(), (String) isNull());
        final FileSystem<?> fs = captor.getValue();
        assertEquals("JFAT", fs.getType().getName());
        assertSame(device, fs.getDevice());
    }

    @Test
    public void testReadOnlyMountIsHonoured() throws Exception {
        System.setProperty("jnode.earlymount.readonly", "true");
        copyFixture("test/fs/jfat/test.fat16", "jfat-earlymount");
        when(dm.getDevice(DEVICE_ID)).thenReturn(device);

        newPlugin().start();

        final ArgumentCaptor<FileSystem> captor = ArgumentCaptor.forClass(FileSystem.class);
        verify(fss).mount(eq("/data"), captor.capture(), (String) isNull());
        assertTrue("the created filesystem must be read-only",
            captor.getValue().isReadOnly());
    }

    @Test
    public void testMissingDeviceNotRequiredIsSkipped() throws Exception {
        when(dm.getDevice(DEVICE_ID)).thenThrow(new DeviceNotFoundException(DEVICE_ID));

        newPlugin().start();

        verify(fss, never()).mount(anyString(), any(FileSystem.class), anyString());
        assertFalse("the claim must be released after the plugin gave up",
            EarlyMountClaims.isClaimed(DEVICE_ID));
    }

    @Test
    public void testMissingDeviceRequiredFailsPluginStart() throws Exception {
        System.setProperty("jnode.earlymount.required", "true");
        when(dm.getDevice(DEVICE_ID)).thenThrow(new DeviceNotFoundException(DEVICE_ID));

        try {
            newPlugin().start();
            fail("expected a PluginException for the missing device");
        } catch (PluginException e) {
            assertTrue("unexpected message: " + e.getMessage(),
                e.getMessage().contains("not found"));
        }
        assertFalse("the claim must be released even when the start fails",
            EarlyMountClaims.isClaimed(DEVICE_ID));
    }

    @Test
    public void testAlreadyMountedJFATInstanceIsReused() throws Exception {
        copyFixture("test/fs/jfat/test.fat16", "jfat-earlymount");
        when(dm.getDevice(DEVICE_ID)).thenReturn(device);
        final FileSystem<?> fs = new FatFileSystemType().create(device, false);
        doReturn(fs).when(fss).getFileSystem(device);
        final Map<String, String> mounts = new HashMap<String, String>();
        mounts.put(device.getId(), "/devices/" + DEVICE_ID);
        when(fss.getDeviceMountPoints()).thenReturn(mounts);

        newPlugin().start();

        verify(fss).mount(eq("/data"), same((FileSystem) fs), (String) isNull());
    }

    @Test
    public void testLegacyFatMountIsReplacedByJFAT() throws Exception {
        copyFixture("test/fs/jfat/test.fat16", "jfat-earlymount");
        when(dm.getDevice(DEVICE_ID)).thenReturn(device);

        // a filesystem registered on the device by another driver (the
        // legacy FAT type), mounted at the conventional path
        final FileSystemType<?> legacyType = mock(FileSystemType.class);
        when(legacyType.getName()).thenReturn("FAT");
        final FileSystem<?> legacyFs = mock(FileSystem.class);
        when(legacyFs.getDevice()).thenReturn((Device) device);
        doReturn(legacyType).when(legacyFs).getType();
        doReturn(legacyFs).when(fss).getFileSystem(device);
        final Map<String, String> mounts = new HashMap<String, String>();
        mounts.put(device.getId(), "/devices/" + DEVICE_ID);
        when(fss.getDeviceMountPoints()).thenReturn(mounts);

        newPlugin().start();

        verify(fss).unmount("/devices/" + DEVICE_ID);
        verify(fss).unregisterFileSystem(device);
        final ArgumentCaptor<FileSystem> captor = ArgumentCaptor.forClass(FileSystem.class);
        verify(fss).mount(eq("/data"), captor.capture(), (String) isNull());
        assertEquals("JFAT", captor.getValue().getType().getName());
        assertNotSame("the legacy instance must have been replaced",
            legacyFs, captor.getValue());
    }

    @Test
    public void testConfigurationFromKernelCmdLine() throws Exception {
        System.clearProperty("jnode.earlymount.device");
        System.clearProperty("jnode.earlymount.mountpoint");
        System.setProperty("jnode.cmdline", "mp=no kdb earlymount=" + DEVICE_ID + ":/data");
        copyFixture("test/fs/jfat/test.fat16", "jfat-earlymount");
        when(dm.getDevice(DEVICE_ID)).thenReturn(device);

        newPlugin().start();

        verify(fss).mount(eq("/data"), any(FileSystem.class), (String) isNull());
    }

    private EarlyMountPlugin newPlugin() {
        return new EarlyMountPlugin(mock(PluginDescriptor.class));
    }

    private void copyFixture(String fixture, String prefix) throws Exception {
        final File source = FileSystemTestUtils.getTestFile(fixture);
        tempImage = File.createTempFile(prefix, ".img");
        tempImage.deleteOnExit();
        final InputStream in = new FileInputStream(source);
        try {
            final OutputStream out = new FileOutputStream(tempImage);
            try {
                final byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        device = new FileDevice(tempImage, "rw");
    }
}