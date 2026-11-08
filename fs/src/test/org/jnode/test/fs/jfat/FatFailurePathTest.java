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
import java.util.Arrays;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatCache;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.jnode.partitions.PartitionTableEntry;
import org.jnode.partitions.ibm.IBMPartitionTableEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatFailurePathTest {

    private static class FailingFileDevice extends FileDevice {
        private boolean armed;
        private boolean runtimeArmed;
        private int writesBeforeFail;

        private FailingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        private void armFailAfterWrites(int n) {
            armed = true;
            writesBeforeFail = n;
        }

        private void armRuntimeFailAfterWrites(int n) {
            runtimeArmed = true;
            writesBeforeFail = n;
        }

        @Override
        public void write(long devOffset, ByteBuffer srcBuf) throws IOException {
            if (runtimeArmed) {
                if (writesBeforeFail == 0) {
                    runtimeArmed = false;
                    throw new IllegalStateException("simulated runtime device failure");
                }
                writesBeforeFail--;
            }
            if (armed) {
                if (writesBeforeFail == 0) {
                    armed = false;
                    throw new IOException("simulated device failure");
                }
                writesBeforeFail--;
            }
            super.write(devOffset, srcBuf);
        }
    }

    private static class PartitionedFileDevice extends FileDevice {
        private final PartitionTableEntry entry;

        private PartitionedFileDevice(File file, String mode, long nrSectors) throws Exception {
            super(file, mode);
            IBMPartitionTableEntry partition = new IBMPartitionTableEntry(null, new byte[512], 0);
            partition.setStartLba(0);
            partition.setNrSectors(nrSectors);
            entry = partition;
        }

        @Override
        public PartitionTableEntry getPartitionTableEntry() {
            return entry;
        }
    }

    private File diskFile;
    private FailingFileDevice device;
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
        diskFile = File.createTempFile("jfat-failure-path-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
            }
            device = null;
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        device = new FailingFileDevice(diskFile, "rw");
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(device);
    }

    private int[] snapshotFat(Fat fat) throws Exception {
        int[] snap = new int[fat.size()];
        for (int i = 0; i < fat.size(); i++) {
            snap[i] = fat.get(i);
        }
        return snap;
    }

    @Test
    public void testEvictionFlushFailureKeepsCacheUsable() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FatCache cache = new FatCache(fat, 2, 512);
        cache.setInt32(2, 0x11111111);
        cache.setInt32(130, 0x22222222);
        assertEquals(2, cache.usedEntries());
        device.armFailAfterWrites(0);
        try {
            cache.getUInt32(256);
            fail("expected IOException from eviction flush");
        } catch (IOException expected) {
        }
        long value = cache.getUInt32(256);
        assertEquals(0L, value);
        cache.setInt32(2, 0x33333333);
        assertEquals(0x33333333L, cache.getUInt32(2));
        assertEquals(2, cache.usedEntries());
        fs.close();
    }

    @Test
    public void testEvictionFlushRuntimeFailureKeepsCacheUsable() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FatCache cache = new FatCache(fat, 2, 512);
        cache.setInt32(2, 0x11111111);
        cache.setInt32(130, 0x22222222);
        assertEquals(2, cache.usedEntries());
        device.armRuntimeFailAfterWrites(0);
        try {
            cache.getUInt32(256);
            fail("expected RuntimeException from eviction flush");
        } catch (IllegalStateException expected) {
        }
        long value = cache.getUInt32(256);
        assertEquals(0L, value);
        cache.setInt32(2, 0x33333333);
        assertEquals(0x33333333L, cache.getUInt32(2));
        assertEquals(2, cache.usedEntries());
        fs.close();
    }

    @Test
    public void testEvictionFlushFailurePreservesDirtyEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FatCache cache = new FatCache(fat, 2, 512);
        cache.setInt32(2, 0x11111111);
        cache.setInt32(130, 0x22222222);
        assertEquals(2, cache.usedEntries());
        device.armFailAfterWrites(0);
        try {
            cache.getUInt32(256);
            fail("expected IOException from eviction flush");
        } catch (IOException expected) {
        }
        assertEquals(0x11111111L, cache.getUInt32(2));
        assertEquals(0x22222222L, cache.getUInt32(130));
        assertEquals(2, cache.usedEntries());
        cache.getUInt32(256);
        cache.getUInt32(384);
        cache.getUInt32(512);
        cache.getUInt32(640);
        assertEquals(0x11111111L, cache.getUInt32(2));
        assertEquals(0x22222222L, cache.getUInt32(130));
        assertEquals(2, cache.usedEntries());
        fs.close();
    }

    @Test
    public void testEvictionFlushRuntimeFailurePreservesDirtyEntry() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FatCache cache = new FatCache(fat, 2, 512);
        cache.setInt32(2, 0x11111111);
        cache.setInt32(130, 0x22222222);
        assertEquals(2, cache.usedEntries());
        device.armRuntimeFailAfterWrites(0);
        try {
            cache.getUInt32(256);
            fail("expected RuntimeException from eviction flush");
        } catch (IllegalStateException expected) {
        }
        assertEquals(0x11111111L, cache.getUInt32(2));
        assertEquals(0x22222222L, cache.getUInt32(130));
        assertEquals(2, cache.usedEntries());
        cache.getUInt32(256);
        cache.getUInt32(384);
        cache.getUInt32(512);
        cache.getUInt32(640);
        assertEquals(0x11111111L, cache.getUInt32(2));
        assertEquals(0x22222222L, cache.getUInt32(130));
        assertEquals(2, cache.usedEntries());
        fs.close();
    }

    @Test
    public void testEofMarkersAndCorruptChainIsLoud() throws Exception {
        File fat12File = Fat12ImageBuilder.createImageFile("jfat12-eof");
        FileDevice roDevice = new FileDevice(fat12File, "r");
        try {
            FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
            FatFileSystem fs12 = type.create(roDevice, true);
            try {
                Fat fat12 = fs12.getFat();
                assertTrue(fat12.isEofChain(0xFF8));
                assertTrue(fat12.isEofChain(0xFFF));
                assertFalse(fat12.isEofChain(0xFEF));
                assertFalse(fat12.isEofChain(0xFF0));
                assertFalse(fat12.isEofChain(0xFF6));
                assertFalse(fat12.isEofChain(0xFF7));
                assertEquals(0xFF8, fat12.eofChain());
                assertTrue(fat12.isBadCluster(0xFF7));
                assertTrue(fat12.isReservedEntry(0xFF0));
                assertTrue(fat12.isCorruptChainEntry(0xFF7));
                assertTrue(fat12.isCorruptChainEntry(0xFF0));
                assertTrue(fat12.isCorruptChainEntry(0xFEF));
                assertFalse(fat12.isCorruptChainEntry(0xFFF));
            } finally {
                fs12.close();
            }
        } finally {
            roDevice.close();
            fat12File.delete();
        }

        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        assertTrue(fat.isEofChain(0x0FFFFFF8));
        assertFalse(fat.isEofChain(0x0FFFFFF7));
        assertFalse(fat.isEofChain(0x0FFFFFF0));
        FSFile file = fs.getRootEntry().getDirectory().addFile("corrupt.bin").getFile();
        byte[] data = new byte[fs.getClusterSize() * 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("corrupt.bin");
        int head = ((FatEntry) entry).getStartCluster();
        assertTrue(head >= fat.firstCluster());
        fat.set(head, 0x0FFFFFF7);
        try {
            new FatChain(fs, head).size();
            fail("expected IOException for bad cluster marker");
        } catch (IOException expected) {
        }
        fat.set(head, fat.size() + 10);
        try {
            new FatChain(fs, head).size();
            fail("expected IOException for out of bounds entry");
        } catch (IOException expected) {
        }
        fs.close();
    }

    @Test
    public void testFailedWriteLeavesNoOrphanedChain() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FSFile file = fs.getRootEntry().getDirectory().addFile("leak.bin").getFile();
        byte[] first = new byte[fs.getClusterSize()];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i & 0xFF);
        }
        file.write(0, ByteBuffer.wrap(first));
        file.flush();
        fs.flush();
        int[] before = snapshotFat(fat);
        int freeBefore = fat.freeEntries();
        long lengthBefore = file.getLength();
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("leak.bin");
        int chainSizeBefore = ((FatEntry) entry).getChain().size();
        byte[] extra = new byte[fs.getClusterSize() * 2];
        for (int i = 0; i < extra.length; i++) {
            extra[i] = (byte) ((i + 1) & 0xFF);
        }
        device.armFailAfterWrites(0);
        try {
            file.write(lengthBefore, ByteBuffer.wrap(extra));
            fail("expected IOException from failing device");
        } catch (IOException expected) {
        }
        int[] after = snapshotFat(fat);
        assertTrue("FAT state after failed write must match state before: "
            + Arrays.toString(before) + " vs " + Arrays.toString(after),
            Arrays.equals(before, after));
        assertEquals(freeBefore, fat.freeEntries());
        assertEquals(lengthBefore, file.getLength());
        assertEquals(chainSizeBefore, ((FatEntry) entry).getChain().size());
        byte[] retry = new byte[fs.getClusterSize()];
        for (int i = 0; i < retry.length; i++) {
            retry[i] = (byte) ((i + 2) & 0xFF);
        }
        file.write(lengthBefore, ByteBuffer.wrap(retry));
        file.flush();
        ByteBuffer readBuffer = ByteBuffer.allocate((int) file.getLength());
        file.read(0, readBuffer);
        readBuffer.flip();
        byte[] actual = new byte[readBuffer.remaining()];
        readBuffer.get(actual);
        assertEquals(lengthBefore + retry.length, actual.length);
        for (int i = 0; i < retry.length; i++) {
            assertEquals(retry[i], actual[(int) lengthBefore + i]);
        }
        fs.close();
    }

    @Test
    public void testFailedWriteToNewFileLeavesNoOrphanedChain() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        Fat fat = fs.getFat();
        FSFile file = fs.getRootEntry().getDirectory().addFile("new.bin").getFile();
        file.flush();
        fs.flush();
        int[] before = snapshotFat(fat);
        int freeBefore = fat.freeEntries();
        assertEquals(0L, file.getLength());
        assertEquals(0, ((FatEntry) file).getStartCluster());
        assertEquals(0, ((FatEntry) file).getChain().size());
        byte[] extra = new byte[fs.getClusterSize() * 2];
        for (int i = 0; i < extra.length; i++) {
            extra[i] = (byte) ((i + 3) & 0xFF);
        }
        device.armFailAfterWrites(0);
        try {
            file.write(0, ByteBuffer.wrap(extra));
            fail("expected IOException from failing device");
        } catch (IOException expected) {
        }
        int[] after = snapshotFat(fat);
        assertTrue("FAT state after failed write to a new file must match state before: "
            + Arrays.toString(before) + " vs " + Arrays.toString(after),
            Arrays.equals(before, after));
        assertEquals(freeBefore, fat.freeEntries());
        assertEquals(0L, file.getLength());
        assertEquals(0, ((FatEntry) file).getStartCluster());
        assertEquals(0, ((FatEntry) file).getChain().size());
        byte[] retry = new byte[fs.getClusterSize()];
        for (int i = 0; i < retry.length; i++) {
            retry[i] = (byte) ((i + 4) & 0xFF);
        }
        file.write(0, ByteBuffer.wrap(retry));
        file.flush();
        assertEquals(retry.length, file.getLength());
        assertEquals(1, ((FatEntry) file).getChain().size());
        ByteBuffer readBuffer = ByteBuffer.allocate((int) file.getLength());
        file.read(0, readBuffer);
        readBuffer.flip();
        byte[] actual = new byte[readBuffer.remaining()];
        readBuffer.get(actual);
        assertEquals(retry.length, actual.length);
        for (int i = 0; i < retry.length; i++) {
            assertEquals(retry[i], actual[i]);
        }
        fs.close();
    }

    @Test
    public void testSectorCountAboveIntMaxIsRejectedAsTooBig() throws Exception {
        long[] tooBig = new long[] {Integer.MAX_VALUE + 1L, 0xFFFFFFFFL};
        for (int i = 0; i < tooBig.length; i++) {
            PartitionedFileDevice partitioned = new PartitionedFileDevice(diskFile, "rw", tooBig[i]);
            partitioned.setLength(1024L * 1024);
            try {
                new FatFileSystemFormatter(ClusterSize._1Kb).format(partitioned);
                fail("expected FileSystemException for " + tooBig[i] + " sectors");
            } catch (FileSystemException expected) {
                assertTrue("expected too big message for " + tooBig[i] + " sectors: "
                    + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("too big"));
                assertFalse("must not report too small for " + tooBig[i] + " sectors: "
                    + expected.getMessage(),
                    expected.getMessage().contains("too small"));
            } finally {
                partitioned.close();
            }
        }
    }

    @Test
    public void testSectorCountAtIntMaxIsNotRejectedAsTooBig() throws Exception {
        PartitionedFileDevice partitioned =
            new PartitionedFileDevice(diskFile, "rw", Integer.MAX_VALUE);
        partitioned.setLength(80L * 1024 * 1024);
        try {
            FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._64Kb).format(partitioned);
            try {
                assertTrue(fs.getBootSector().isFat32());
                assertTrue(fs.getBootSector().getCountOfClusters() > 0);
                assertEquals(65536, fs.getBootSector().getClusterSize());
            } finally {
                fs.close();
            }
        } finally {
            partitioned.close();
        }
    }
}
