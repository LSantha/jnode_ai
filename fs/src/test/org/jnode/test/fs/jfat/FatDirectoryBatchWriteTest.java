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

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Counts device writes for create/delete of files whose directory entries
 * include long-file-name components. With 1 KiB clusters (32 entries per
 * cluster) a mangled create publishes n LFN entries plus the short entry, so
 * the whole batch is expected to land in a single cluster write.
 */
public class FatDirectoryBatchWriteTest {

    private static final int ENTRIES_PER_CLUSTER = 32;

    private static class CountingFileDevice extends FileDevice {
        private int writeCount;
        private int maxWriteLength;

        private CountingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        @Override
        public void write(long devOffset, ByteBuffer srcBuf) throws java.io.IOException {
            writeCount++;
            if (srcBuf.remaining() > maxWriteLength)
                maxWriteLength = srcBuf.remaining();
            super.write(devOffset, srcBuf);
        }

        private void resetCounts() {
            writeCount = 0;
            maxWriteLength = 0;
        }
    }

    private File diskFile;
    private CountingFileDevice device;
    private FileDevice currentDevice;

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
        FileSystemPlugin fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-batch-write-test", ".img");
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
        device = new CountingFileDevice(diskFile, "rw");
        currentDevice = device;
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(device);
    }

    private String lfnName(int components) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components * 13 - 4; i++)
            sb.append('a');
        sb.append(".txt");
        return sb.toString();
    }

    private static int expectedClusterWrites(int entries) {
        return (entries + ENTRIES_PER_CLUSTER - 1) / ENTRIES_PER_CLUSTER;
    }

    @Test
    public void testCreateLfnFileBatchesDirectoryEntries() throws Exception {
        for (int n : new int[] {1, 5, 12}) {
            FatFileSystem fs = formatAndOpenRW();
            try {
                FSDirectory root = fs.getRootEntry().getDirectory();
                device.resetCounts();
                root.addFile(lfnName(n));
                int expected = expectedClusterWrites(n + 1);
                assertTrue("LFN create with " + n + " components wrote " + device.writeCount
                    + " device writes, expected at most " + expected, device.writeCount <= expected);
                assertEquals("the batched write must span all " + (n + 1) + " entries",
                    (n + 1) * 32, device.maxWriteLength);
            } finally {
                fs.close();
            }
        }
    }

    @Test
    public void testDeleteLfnFileBatchesDirectoryEntries() throws Exception {
        for (int n : new int[] {1, 5, 12}) {
            FatFileSystem fs = formatAndOpenRW();
            try {
                FSDirectory root = fs.getRootEntry().getDirectory();
                FatEntry entry = (FatEntry) root.addFile(lfnName(n));
                device.resetCounts();
                entry.delete();
                int expected = expectedClusterWrites(n + 1);
                assertTrue("LFN delete with " + n + " components wrote " + device.writeCount
                    + " device writes, expected at most " + expected, device.writeCount <= expected);
                assertEquals("the batched delete write must span all " + (n + 1) + " entries",
                    (n + 1) * 32, device.maxWriteLength);
            } finally {
                fs.close();
            }
        }
    }

    @Test
    public void testCreateShortNameFileWritesOneEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            device.resetCounts();
            root.addFile("s.txt");
            assertEquals("short-name create wrote " + device.writeCount + " device writes",
                1, device.writeCount);
            assertEquals("short-name create must write a single 32-byte entry", 32, device.maxWriteLength);
        } finally {
            fs.close();
        }
    }

    @Test
    public void testDeleteShortNameFileWritesOneEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            FatEntry entry = (FatEntry) root.addFile("s.txt");
            device.resetCounts();
            entry.delete();
            assertEquals("short-name delete wrote " + device.writeCount + " device writes",
                1, device.writeCount);
            assertEquals("short-name delete must write a single 32-byte entry", 32, device.maxWriteLength);
        } finally {
            fs.close();
        }
    }
}
