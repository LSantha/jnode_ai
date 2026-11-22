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

package org.jnode.fs.service.def;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;

import org.jnode.driver.Device;
import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the claim check in {@link FileSystemMounter#tryToMount}: a
 * device claimed by the earlymount plugin must not be mounted by the
 * mounter, and an unclaimed device must still be mounted normally.
 */
public class FileSystemMounterClaimTest {

    private FileSystemService fss;
    private File tempImage;

    @Before
    public void setUp() throws Exception {
        fss = mock(FileSystemService.class);
        when(fss.getFileSystem(any(Device.class))).thenReturn(null);
        final Collection<FileSystemType<?>> types =
            Arrays.<FileSystemType<?>>asList(new FatFileSystemType());
        when(fss.fileSystemTypes()).thenReturn(types);
    }

    @After
    public void tearDown() throws Exception {
        if (tempImage != null) {
            tempImage.delete();
            tempImage = null;
        }
    }

    @Test
    public void testClaimedDeviceIsSkipped() throws Exception {
        final FileDevice device = openFixture("test/fs/jfat/test.fat16", "jfat-mounter-claim");
        try {
            EarlyMountClaims.claim(device.getId());
            try {
                new FileSystemMounter(fss).tryToMount(device, device, false, false);
                verify(fss, never()).registerFileSystem(any(FileSystem.class));
                verify(fss, never()).mount(anyString(), any(FileSystem.class), anyString());
            } finally {
                EarlyMountClaims.release(device.getId());
            }
        } finally {
            device.close();
        }
    }

    @Test
    public void testUnclaimedDeviceIsMounted() throws Exception {
        final FileDevice device = openFixture("test/fs/jfat/test.fat16", "jfat-mounter-unclaimed");
        try {
            new FileSystemMounter(fss).tryToMount(device, device, false, false);

            final ArgumentCaptor<FileSystem> captor = ArgumentCaptor.forClass(FileSystem.class);
            verify(fss).registerFileSystem(captor.capture());
            final FileSystem<?> fs = captor.getValue();
            assertNotNull("an unclaimed device must be mounted by the mounter", fs);
            assertSame(device, fs.getDevice());
            verify(fss).mount(eq("/devices/" + device.getId()), same((FileSystem) fs), (String) isNull());
        } finally {
            device.close();
        }
    }

    private FileDevice openFixture(String fixture, String prefix) throws Exception {
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
        return new FileDevice(tempImage, "rw");
    }
}