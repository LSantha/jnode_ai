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

public class FatDirectoryIndexTest {

    private static final int FILE_COUNT = 30;

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

        diskFile = File.createTempFile("jfat-index-test", ".img");
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
    public void testRepeatedLookupsDoNotHitDisk() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            createFiles(rootDir);
            fs.flush();

            assertNotNull(rootDir.getEntry("file0.txt"));
            countingApi.getAndResetReadCount();

            for (int i = 0; i < FILE_COUNT; i++) {
                assertNotNull(rootDir.getEntry("file" + i + ".txt"));
            }
            for (int i = 0; i < FILE_COUNT; i++) {
                FSEntry entry = rootDir.getEntry("file" + i + ".txt");
                assertEquals("file" + i + ".txt", entry.getName());
                assertEquals("file" + i + ".txt", ((FatDirectory) rootDir).getEntryByName("FILE" + i + ".TXT").getName());
                byte[] shortName = ((FatEntry) entry).getEntry().getName();
                assertSame(entry, ((FatDirectory) rootDir).getEntryByShortName(shortName));
                assertSame(entry, rootDir.getEntryById(entry.getId()));
            }
            assertNull(rootDir.getEntry("absent.txt"));
            assertNull(((FatDirectory) rootDir).getEntryByName("AbSeNt.Txt"));
            assertNull(rootDir.getEntryById("1048576"));
            assertTrue(((FatDirectory) rootDir).collide("file3.txt"));
            assertFalse(((FatDirectory) rootDir).collide("absent.txt"));

            assertEquals("second and later lookups must be served from the cache", 0, countingApi.readCount());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testOrdinaryMutationsDoNotForceRescans() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 0; i < 10; i++) {
                rootDir.addFile("scan" + i + ".txt").getFile();
            }
            fs.flush();
            rootDir.getEntry("scan0.txt");
            countingApi.getAndResetReadCount();

            rootDir.addFile("fresh.txt");
            fs.flush();
            rootDir.remove("fresh.txt");
            rootDir.addFile("fresh.txt");
            fs.flush();
            rootDir.getEntry("fresh.txt");
            countingApi.getAndResetReadCount();

            for (int i = 0; i < 20; i++) {
                int n = i % 10;
                assertNotNull(rootDir.getEntry("scan" + n + ".txt"));
                assertNotNull(rootDir.getEntry("fresh.txt"));
                assertNull(((FatDirectory) rootDir).getEntryByName("nope" + i + ".txt"));
            }
            for (int i = 0; i < 10; i++) {
                assertNotNull(rootDir.getEntry("scan" + i + ".txt"));
            }
            assertEquals("lookups after ordinary create and delete must not read from disk", 0, countingApi.getAndResetReadCount());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testAtMostOneFullScanPerInvalidation() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 0; i < 10; i++) {
                rootDir.addFile("scan" + i + ".txt").getFile();
            }
            fs.flush();
            assertNotNull(rootDir.getEntry("scan0.txt"));
            countingApi.getAndResetReadCount();

            renameEntry(rootDir, "scan0.txt", "scan0a.txt");
            assertNotNull(rootDir.getEntry("scan0a.txt"));
            long oneScan = countingApi.getAndResetReadCount();
            assertTrue("rename must force a full scan", oneScan > 0);

            renameEntry(rootDir, "scan0a.txt", "scan0.txt");
            countingApi.getAndResetReadCount();
            for (int i = 0; i < 20; i++) {
                int n = i % 10;
                assertNotNull(rootDir.getEntry("scan" + n + ".txt"));
                assertNotNull(rootDir.getEntryById(rootDir.getEntry("scan" + n + ".txt").getId()));
                assertNull(((FatDirectory) rootDir).getEntryByName("nope" + i + ".txt"));
            }
            long groupScans = countingApi.getAndResetReadCount();
            assertEquals("one invalidation must trigger at most one full scan", oneScan, groupScans);

            for (int i = 0; i < 10; i++) {
                assertNotNull(rootDir.getEntry("scan" + i + ".txt"));
            }
            assertEquals("cached lookups must not read from disk", 0, countingApi.getAndResetReadCount());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testMixedCaseLookupSemanticsUnchanged() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            FSEntry mangled = rootDir.addFile("MixedCase.txt");
            FSEntry plain = rootDir.addFile("simple.txt");
            fs.flush();

            String[] variants = {
                "mIXEDCASE.TXT",
                "MIXEDCASE.TXT",
                "mixedcase.txt",
                "MixedCase.TxT"
            };
            for (int i = 0; i < variants.length; i++) {
                assertSame(mangled, rootDir.getEntry(variants[i]));
                assertSame(mangled, ((FatDirectory) rootDir).getEntryByName(variants[i]));
            }
            assertSame(plain, rootDir.getEntry("SIMPLE.TXT"));
            assertSame(plain, ((FatDirectory) rootDir).getEntryByName("Simple.Txt"));
            assertNull(((FatDirectory) rootDir).getEntryByName("MixedCase.txx"));
            assertNull(rootDir.getEntry("MIXEDCASE.TXX"));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testRemovedAndRecreatedFileIsNotShadowed() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            rootDir.addFile("gone.txt");
            fs.flush();
            rootDir.remove("gone.txt");

            assertNull(rootDir.getEntry("gone.txt"));
            assertNull(((FatDirectory) rootDir).getEntryByName("GONE.TXT"));
            assertFalse(((FatDirectory) rootDir).collide("gone.txt"));

            FSEntry recreated = rootDir.addFile("gone.txt");
            assertNotNull(recreated);
            assertSame(recreated, rootDir.getEntry("gone.txt"));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testGetEntryByIdPreservesDeletedEntrySemantics() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            FSEntry entry = rootDir.addFile("deleted-id.txt");
            String id = entry.getId();
            fs.flush();
            rootDir.remove("deleted-id.txt");

            assertNull(rootDir.getEntry("deleted-id.txt"));
            FSEntry byId = rootDir.getEntryById(id);
            assertNotNull("getEntryById includes deleted entries", byId);
            assertEquals(id, byId.getId());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testGetEntryByShortNameRejectsIllegalLength() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            try {
                ((FatDirectory) rootDir).getEntryByShortName(new byte[3]);
                fail("expected IllegalArgumentException for short name length 3");
            } catch (IllegalArgumentException expected) {
                assertEquals("illegal shortname length: 3", expected.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testLookupConsistencyAcrossMethods() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            FSEntry entry = rootDir.addFile("consistent.txt");
            fs.flush();

            FSEntry byName = rootDir.getEntry("consistent.txt");
            FSEntry byName2 = ((FatDirectory) rootDir).getEntryByName("CONSISTENT.TXT");
            FSEntry byId = rootDir.getEntryById(entry.getId());
            byte[] shortName = ((FatEntry) entry).getEntry().getName();
            FSEntry byShortName = ((FatDirectory) rootDir).getEntryByShortName(shortName);

            assertSame(entry, byName);
            assertSame(entry, byName2);
            assertSame(entry, byId);
            assertSame(entry, byShortName);
        } finally {
            fs.close();
        }
    }

    @Test
    public void testDeletedSlotIsReusedOnRecreate() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 0; i < 12; i++) {
                rootDir.addFile("slot" + i + ".txt");
            }
            FSEntry victim = rootDir.getEntry("slot3.txt");
            String victimIndex = victim.getId();
            fs.flush();

            rootDir.remove("slot3.txt");
            FSEntry recreated = rootDir.addFile("slot3.txt");
            fs.flush();

            assertEquals("a recreated file must reuse the freed slot instead of extending the directory",
                victimIndex, recreated.getId());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testDeletedSlotIsReusedOnRecreateInGrowingDirectory() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            FSDirectory subdir = rootDir.addDirectory("growth").getDirectory();
            for (int i = 0; i < 40; i++) {
                subdir.addFile("grow" + i + ".txt");
            }
            FSEntry victim = subdir.getEntry("grow5.txt");
            String victimIndex = victim.getId();
            fs.flush();

            subdir.remove("grow5.txt");
            FSEntry recreated = subdir.addFile("grow5.txt");
            fs.flush();

            assertEquals("a freed slot must stay reusable after the directory grew past one cluster",
                victimIndex, recreated.getId());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testRenameSiblingPreservesUnflushedFileInstance() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            FatEntry original = (FatEntry) rootDir.addFile("writer-longname.txt");
            byte[] payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            original.getFile().write(0, ByteBuffer.wrap(payload));
            int startCluster = original.getStartCluster();

            FSEntry sibling = rootDir.addFile("other.txt");
            sibling.setName("other2.txt");

            FSEntry lookedUp = rootDir.getEntry("writer-longname.txt");
            assertSame("renaming a sibling must not replace a live entry with unflushed writes", original, lookedUp);
            assertEquals(payload.length, lookedUp.getFile().getLength());
            assertEquals(startCluster, ((FatEntry) lookedUp).getStartCluster());
        } finally {
            fs.close();
        }
    }

    private void renameEntry(FSDirectory rootDir, String from, String to) throws IOException {
        FatEntry entry = (FatEntry) rootDir.getEntry(from);
        assertNotNull(entry);
        entry.setName(to);
        countingApi.getAndResetReadCount();
    }

    private void createFiles(FSDirectory rootDir) throws IOException {
        for (int i = 0; i < FILE_COUNT; i++) {
            FSEntry entry = rootDir.addFile("file" + i + ".txt");
            entry.getFile().write(0, ByteBuffer.wrap(new byte[] {(byte) i}));
        }
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
