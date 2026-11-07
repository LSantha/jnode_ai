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

package org.jnode.test.fs.jfat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FatFlushOrderTest {

    private static final String FILE_NAME = "hello.txt";

    private static class WriteEvent {
        private final long offset;
        private final int length;
        private final boolean flush;

        private WriteEvent(long offset, int length, boolean flush) {
            this.offset = offset;
            this.length = length;
            this.flush = flush;
        }
    }

    private static class RecordingFileDevice extends FileDevice {
        private final List<WriteEvent> events = new ArrayList<WriteEvent>();
        private boolean armCrashOnFlush;
        private boolean dropping;

        private RecordingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        @Override
        public void write(long devOffset, ByteBuffer srcBuf) throws java.io.IOException {
            int length = srcBuf.remaining();
            events.add(new WriteEvent(devOffset, length, false));
            if (dropping) {
                srcBuf.position(srcBuf.position() + length);
                return;
            }
            super.write(devOffset, srcBuf);
        }

        @Override
        public void flush() {
            events.add(new WriteEvent(-1, 0, true));
            if (armCrashOnFlush) {
                dropping = true;
            }
        }

        private void clearEvents() {
            events.clear();
        }

        private void armCrashOnNextFlush() {
            armCrashOnFlush = true;
        }
    }

    private File diskFile;
    private RecordingFileDevice device;
    private FileDevice currentDevice;
    private FileSystemService fss;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }

        DummyPluginDescriptor desc = new DummyPluginDescriptor(true);
        DummyExtensionPoint ep = new DummyExtensionPoint("types", "org.jnode.fs.types", "types");
        desc.addExtensionPoint(ep);
        for (String className : new String[]{
            "org.jnode.fs.jfat.FatFileSystemType",
            "org.jnode.fs.ext2.Ext2FileSystemType",
            "org.jnode.fs.fat.FatFileSystemType",
            "org.jnode.fs.exfat.ExFatFileSystemType",
            "org.jnode.fs.iso9660.ISO9660FileSystemType",
            "org.jnode.fs.hfsplus.HfsPlusFileSystemType",
            "org.jnode.fs.ntfs.NTFSFileSystemType",
            "org.jnode.fs.jifs.JifsFileSystemType"
        }) {
            DummyExtension extension = new DummyExtension();
            DummyConfigurationElement element = new DummyConfigurationElement();
            element.addAttribute("class", className);
            extension.addElement(element);
            ep.addExtension(extension);
        }
        fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-flush-order-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (currentDevice != null) {
            try {
                currentDevice.close();
            } catch (Exception e) {
            }
            currentDevice = null;
        }
        device = null;
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        device = new RecordingFileDevice(diskFile, "rw");
        currentDevice = device;
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(device);
    }

    private FatFileSystem openReadOnly() throws Exception {
        if (currentDevice != null) {
            currentDevice.close();
            currentDevice = null;
        }
        FileDevice roDevice = new FileDevice(diskFile, "r");
        currentDevice = roDevice;
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(roDevice, true);
    }

    private FatFileSystem openRW() throws Exception {
        if (currentDevice != null) {
            currentDevice.close();
            currentDevice = null;
        }
        FileDevice rwDevice = new FileDevice(diskFile, "rw");
        currentDevice = rwDevice;
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(rwDevice, false);
    }

    private byte[] testData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private boolean isFatWrite(FatFileSystem fs, WriteEvent event) {
        if (event.flush) {
            return false;
        }
        Fat fat = fs.getFat();
        int sectorSize = fs.getBootSector().getBytesPerSector();
        for (int i = 0; i < fs.getBootSector().getNrFats(); i++) {
            long start = fat.getFirstSector(i) * (long) sectorSize;
            long end = (fat.getLastSector(i) + 1L) * (long) sectorSize;
            if (event.offset >= start && event.offset < end) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirEntryWrite(FatFileSystem fs, WriteEvent event) {
        if (event.flush) {
            return false;
        }
        Fat fat = fs.getFat();
        long rootStart;
        long rootEnd;
        if (fat.isFat32()) {
            int rootCluster = (int) fs.getBootSector().getRootDirectoryStartCluster();
            rootStart = fat.getClusterPosition(rootCluster);
            rootEnd = rootStart + fs.getClusterSize();
        } else {
            int sectorSize = fs.getBootSector().getBytesPerSector();
            rootStart = fs.getBootSector().getFirstRootDirSector() * (long) sectorSize;
            rootEnd = rootStart + fs.getBootSector().getNrRootDirEntries() * 32L;
        }
        return event.offset >= rootStart && event.offset < rootEnd && event.length == 32;
    }

    @Test
    public void testFatFlushPrecedesDirectoryEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        byte[] data = testData(fs.getClusterSize() * 3);

        FSFile file = fs.getRootEntry().getDirectory().addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(data));

        device.clearEvents();
        file.flush();

        int firstFatWrite = -1;
        int dirEntryWrite = -1;
        int flushBetween = -1;
        for (int i = 0; i < device.events.size(); i++) {
            WriteEvent event = device.events.get(i);
            if (isFatWrite(fs, event) && firstFatWrite < 0) {
                firstFatWrite = i;
            }
            if (isDirEntryWrite(fs, event) && dirEntryWrite < 0) {
                dirEntryWrite = i;
            }
            if (event.flush && firstFatWrite >= 0 && dirEntryWrite < 0) {
                flushBetween = i;
            }
        }

        assertTrue("FAT sectors must be written during file flush", firstFatWrite >= 0);
        assertTrue("directory entry must be written during file flush", dirEntryWrite >= 0);
        assertTrue("FAT sectors must reach the device before the directory entry",
            firstFatWrite < dirEntryWrite);
        assertTrue("device flush must happen after FAT and before directory entry",
            flushBetween > firstFatWrite && flushBetween < dirEntryWrite);

        fs.close();
    }

    @Test
    public void testCrashAfterFatFlushLeavesNoDanglingDirectoryEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        byte[] data = testData(fs.getClusterSize() * 3);

        FSFile file = fs.getRootEntry().getDirectory().addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(data));

        device.armCrashOnNextFlush();
        file.flush();
        fs.close();
        device.close();
        device = null;
        currentDevice = null;

        FatFileSystem roFs = openReadOnly();
        FSDirectory rootDir = roFs.getRootEntry().getDirectory();
        FSEntry entry = rootDir.getEntry(FILE_NAME);
        if (entry != null) {
            FatEntry fatEntry = (FatEntry) entry;
            long length = entry.getFile().getLength();
            if (length > 0) {
                Fat fat = roFs.getFat();
                FatChain chain = fatEntry.getChain();
                FatChain.ChainIterator iterator = chain.listIterator();
                while (iterator.hasNext()) {
                    int cluster = iterator.next();
                    assertFalse("directory entry must not reference a free cluster: " + cluster,
                        fat.isFreeEntry(cluster));
                }
                ByteBuffer readBuffer = ByteBuffer.allocate((int) length);
                entry.getFile().read(0, readBuffer);
            }
        }
        roFs.close();

        FatFileSystem rwFs = openRW();
        byte[] afterData = "after crash".getBytes();
        FSFile afterFile = rwFs.getRootEntry().getDirectory().addFile("after.txt").getFile();
        afterFile.write(0, ByteBuffer.wrap(afterData));
        afterFile.flush();
        rwFs.close();

        FatFileSystem checkFs = openReadOnly();
        FSEntry afterEntry = checkFs.getRootEntry().getDirectory().getEntry("after.txt");
        assertTrue("file created after crash recovery must exist", afterEntry != null);
        ByteBuffer readBuffer = ByteBuffer.allocate(afterData.length);
        afterEntry.getFile().read(0, readBuffer);
        for (int i = 0; i < afterData.length; i++) {
            assertTrue("data after crash recovery must not be corrupted",
                readBuffer.get(i) == afterData[i]);
        }
        checkFs.close();
    }
}
