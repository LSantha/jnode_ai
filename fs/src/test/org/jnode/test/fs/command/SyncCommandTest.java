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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

import org.jnode.driver.Bus;
import org.jnode.driver.Device;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.command.SyncCommand;
import org.jnode.fs.spi.AbstractFileSystem;

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

    public void testFlushFileSystemIssuesFileSystemAndDeviceBarriers() throws Exception {
        SyncCommand command = new SyncCommand();
        RecordingBlockDeviceAPI recordingApi = new RecordingBlockDeviceAPI();
        Device device = new Device(new Bus((Bus) null) {
        }, "syncTestDevice");
        device.registerAPI(BlockDeviceAPI.class, recordingApi);
        RecordingFileSystem fs = new RecordingFileSystem(device);
        StringWriter outText = new StringWriter();
        PrintWriter out = new PrintWriter(outText);
        PrintWriter err = new PrintWriter(outText);

        Method m = SyncCommand.class.getDeclaredMethod("flushFileSystem",
            FileSystem.class, String.class, PrintWriter.class, PrintWriter.class);
        m.setAccessible(true);
        try {
            m.invoke(command, fs, "/", out, err);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }

        assertEquals("FileSystem.flush() must be called", 1, fs.flushCalls);
        assertEquals("BlockDeviceAPI.flush() must be called on the mounted device",
            1, recordingApi.flushCalls);
        assertTrue(outText.toString().contains("synced /"));
    }

    private static class RecordingFileSystem extends AbstractFileSystem<FSEntry> {
        private int flushCalls;

        private RecordingFileSystem(Device device) throws FileSystemException {
            super(device, false, null);
        }

        @Override
        public void flush() {
            flushCalls++;
        }

        public long getTotalSpace() throws IOException {
            return -1;
        }

        public long getFreeSpace() throws IOException {
            return -1;
        }

        public long getUsableSpace() throws IOException {
            return -1;
        }

        public String getVolumeName() throws IOException {
            return "synctest";
        }

        @Override
        protected FSFile createFile(FSEntry entry) throws IOException {
            return null;
        }

        @Override
        protected FSDirectory createDirectory(FSEntry entry) throws IOException {
            return null;
        }

        @Override
        protected FSEntry createRootEntry() throws IOException {
            return null;
        }
    }

    private static class RecordingBlockDeviceAPI implements BlockDeviceAPI {
        private int flushCalls;

        public long getLength() throws IOException {
            return 0;
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
        }

        public void write(long devOffset, ByteBuffer src) throws IOException {
        }

        public void flush() throws IOException {
            flushCalls++;
        }
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
