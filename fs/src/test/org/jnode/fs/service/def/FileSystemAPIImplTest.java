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

package org.jnode.fs.service.def;

import java.io.IOException;
import java.util.Map;

import org.jnode.driver.Device;
import org.jnode.fs.FileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for FileSystemAPIImpl.unmount().
 */
public class FileSystemAPIImplTest {

    private FileSystemAPIImpl api;
    private VirtualFS vfs;

    @Before
    public void setUp() throws Exception {
        FileSystemManager fsm = new FileSystemManager();
        Device device = mock(Device.class);
        when(device.getShortDescription()).thenReturn("mock-device");
        vfs = new VirtualFS(device);
        api = new FileSystemAPIImpl(fsm, vfs);
    }

    @After
    public void tearDown() throws Exception {
        api = null;
        vfs = null;
    }

    @Test
    public void testUnmountNonExistentMountPointThrowsIllegalArgument() {
        try {
            api.unmount("/nonexistent");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Not a mount point"));
        } catch (IOException e) {
            fail("Expected IllegalArgumentException, got IOException");
        }
    }

    @Test
    public void testUnmountWithoutLeadingSlash() throws Exception {
        Device device = mock(Device.class);
        FileSystem<?> fs = mock(FileSystem.class);
        when(fs.getDevice()).thenReturn(device);
        when(fs.isReadOnly()).thenReturn(true);

        api.mount("/mnt", fs, null);
        assertTrue(api.getMountPoints().containsKey("/mnt"));

        api.unmount("mnt");
        assertFalse(api.getMountPoints().containsKey("/mnt"));
    }

    @Test
    public void testUnmountReadOnlyFsClosesAndUnregisters() throws Exception {
        Device device = mock(Device.class);
        FileSystem<?> fs = mock(FileSystem.class);
        when(fs.getDevice()).thenReturn(device);
        when(fs.isReadOnly()).thenReturn(true);

        api.mount("/mnt", fs, null);
        api.unmount("/mnt");

        verify(fs).close();
        assertFalse(api.getMountPoints().containsKey("/mnt"));
    }

    @Test
    public void testUnmountRollbackOnCloseFailure() throws Exception {
        Device device = mock(Device.class);
        FileSystem<?> fs = mock(FileSystem.class);
        when(fs.getDevice()).thenReturn(device);
        when(fs.isReadOnly()).thenReturn(false);

        api.mount("/mnt", fs, null);
        assertTrue(api.getMountPoints().containsKey("/mnt"));

        IOException closeException = new IOException("Close failed");
        doThrow(closeException).when(fs).close();

        try {
            api.unmount("/mnt");
            fail("Expected IOException");
        } catch (IOException e) {
            assertEquals("Close failed", e.getMessage());
        }

        Map<String, FileSystem<?>> mountPoints = api.getMountPoints();
        assertTrue("Mount point should be restored after rollback",
            mountPoints.containsKey("/mnt"));
        assertEquals(fs, mountPoints.get("/mnt"));
    }

    @Test
    public void testUnmountRemovesMountPoint() throws Exception {
        Device device = mock(Device.class);
        FileSystem<?> fs = mock(FileSystem.class);
        when(fs.getDevice()).thenReturn(device);
        when(fs.isReadOnly()).thenReturn(true);

        api.mount("/mnt", fs, null);
        assertTrue(api.getMountPoints().containsKey("/mnt"));

        api.unmount("/mnt");
        assertFalse(api.getMountPoints().containsKey("/mnt"));
        assertEquals(0, api.getMountPoints().size());
    }

    @Test
    public void testUnmountMultipleMountPoints() throws Exception {
        Device device1 = mock(Device.class);
        Device device2 = mock(Device.class);
        FileSystem<?> fs1 = mock(FileSystem.class);
        FileSystem<?> fs2 = mock(FileSystem.class);
        when(fs1.getDevice()).thenReturn(device1);
        when(fs2.getDevice()).thenReturn(device2);
        when(fs1.isReadOnly()).thenReturn(true);
        when(fs2.isReadOnly()).thenReturn(true);

        api.mount("/mnt1", fs1, null);
        api.mount("/mnt2", fs2, null);
        assertEquals(2, api.getMountPoints().size());

        api.unmount("/mnt1");
        assertEquals(1, api.getMountPoints().size());
        assertTrue(api.getMountPoints().containsKey("/mnt2"));

        api.unmount("/mnt2");
        assertEquals(0, api.getMountPoints().size());
    }
}
