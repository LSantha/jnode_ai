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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatDirectory;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.jnode.partitions.PartitionTableEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatShortNameScanCountTest {

    private File diskFile;
    private FileDevice fileDevice;
    private CountingFSBlockDeviceAPI countingApi;
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

        diskFile = File.createTempFile("jfat-scancount-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testShortNameGenerationReadsNoDiskWhenIndexIsBuilt() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            createSameBasisFiles(rootDir, 25);
            fs.flush();
            countingApi.getAndResetReadCount();

            constructShortName((FatDirectory) rootDir, sameBasisName(27));

            assertEquals("short name generation must not read from disk when the index is built",
                0, countingApi.readCount());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testShortNameGenerationPerformsAtMostOneDirectoryScan() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            createSameBasisFiles(rootDir, 12);
            rootDir.addFile("marker.txt");
            renameEntry(rootDir, "marker.txt", "marker2.txt");
            fs.flush();

            countingApi.getAndResetReadCount();
            rootDir.getEntry("marker2.txt");
            long oneScan = countingApi.getAndResetReadCount();
            assertTrue("rename must force a full scan", oneScan > 0);

            renameEntry(rootDir, "marker2.txt", "marker.txt");
            countingApi.getAndResetReadCount();

            constructShortName((FatDirectory) rootDir, sameBasisName(14));

            assertEquals("short name generation must trigger at most one directory scan",
                oneScan, countingApi.readCount());
        } finally {
            fs.close();
        }
    }

    private void createSameBasisFiles(FSDirectory rootDir, int count) throws IOException {
        for (int i = 2; i <= count + 1; i++) {
            FSEntry entry = rootDir.addFile(sameBasisName(i));
            assertNotNull(entry);
        }
    }

    private static String sameBasisName(int dotCount) {
        StringBuilder sb = new StringBuilder("AAAAAAAA");
        for (int i = 0; i < dotCount; i++)
            sb.append('.');
        sb.append("txt");
        return sb.toString();
    }

    private Object constructShortName(FatDirectory dir, String longName) throws Exception {
        Class<?> fatNameClass = Class.forName("org.jnode.fs.jfat.FatName");
        java.lang.reflect.Constructor<?> ctor = fatNameClass.getDeclaredConstructor(FatDirectory.class, String.class);
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(dir, longName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception)
                throw (Exception) e.getCause();
            throw e;
        }
    }

    private void renameEntry(FSDirectory rootDir, String from, String to) throws IOException {
        FatEntry entry = (FatEntry) rootDir.getEntry(from);
        assertNotNull(entry);
        entry.setName(to);
        countingApi.getAndResetReadCount();
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        countingApi = new CountingFSBlockDeviceAPI(fileDevice);
        fileDevice.unregisterAPI(FSBlockDeviceAPI.class);
        fileDevice.unregisterAPI(BlockDeviceAPI.class);
        fileDevice.registerAPI(FSBlockDeviceAPI.class, countingApi);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }

    private static final class CountingFSBlockDeviceAPI implements FSBlockDeviceAPI {
        private final FileDevice delegate;
        private final AtomicLong readCount = new AtomicLong();

        private CountingFSBlockDeviceAPI(FileDevice delegate) {
            this.delegate = delegate;
        }

        public long getLength() throws IOException {
            return delegate.getLength();
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
            readCount.incrementAndGet();
            delegate.read(devOffset, dest);
        }

        public void write(long devOffset, ByteBuffer src) throws IOException {
            delegate.write(devOffset, src);
        }

        public void flush() throws IOException {
            delegate.flush();
        }

        public int getSectorSize() throws IOException {
            return delegate.getSectorSize();
        }

        public PartitionTableEntry getPartitionTableEntry() {
            return delegate.getPartitionTableEntry();
        }

        long readCount() {
            return readCount.get();
        }

        long getAndResetReadCount() {
            return readCount.getAndSet(0);
        }
    }
}
