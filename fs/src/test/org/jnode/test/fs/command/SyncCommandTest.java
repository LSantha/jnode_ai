/*
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

package org.jnode.test.fs.command;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

import org.jnode.driver.Device;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.command.SyncCommand;

public class SyncCommandTest extends TestCase {

    private Map<String, FileSystem<?>> mountPoints;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mountPoints = new HashMap<String, FileSystem<?>>();
        mountPoints.put("/", new StubFileSystem("/"));
        mountPoints.put("/home", new StubFileSystem("/home"));
        mountPoints.put("/home/user", new StubFileSystem("/home/user"));
    }

    public void testExactMountPointMatch() {
        Map.Entry<String, FileSystem<?>> result =
            SyncCommand.findMountPoint(mountPoints, "/home");
        assertNotNull(result);
        assertEquals("/home", result.getKey());
    }

    public void testLongestPrefixMatch() {
        Map.Entry<String, FileSystem<?>> result =
            SyncCommand.findMountPoint(mountPoints, "/home/user/docs");
        assertNotNull(result);
        assertEquals("/home/user", result.getKey());
    }

    public void testNoMatchingMountPoint() {
        Map<String, FileSystem<?>> noRoot = new HashMap<String, FileSystem<?>>();
        noRoot.put("/home", new StubFileSystem("/home"));
        noRoot.put("/home/user", new StubFileSystem("/home/user"));
        Map.Entry<String, FileSystem<?>> result =
            SyncCommand.findMountPoint(noRoot, "/var/log");
        assertNull(result);
    }

    public void testRootMatch() {
        Map.Entry<String, FileSystem<?>> result =
            SyncCommand.findMountPoint(mountPoints, "/var/log");
        assertNotNull(result);
        assertEquals("/", result.getKey());
    }

    public void testExactRootMatch() {
        Map.Entry<String, FileSystem<?>> result =
            SyncCommand.findMountPoint(mountPoints, "/");
        assertNotNull(result);
        assertEquals("/", result.getKey());
    }

    private static class StubFileSystem implements FileSystem<FSEntry> {
        private final String mp;

        StubFileSystem(String mp) {
            this.mp = mp;
        }

        public FileSystemType<? extends FileSystem<FSEntry>> getType() {
            return null;
        }

        public Device getDevice() {
            return null;
        }

        public FSEntry getRootEntry() throws IOException {
            return null;
        }

        public boolean isReadOnly() {
            return false;
        }

        public void close() throws IOException {
        }

        public boolean isClosed() {
            return false;
        }

        public long getTotalSpace() throws IOException {
            return 0;
        }

        public long getFreeSpace() throws IOException {
            return 0;
        }

        public long getUsableSpace() throws IOException {
            return 0;
        }

        public String getVolumeName() throws IOException {
            return mp;
        }
    }
}
