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
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSEntry;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.FatDirectory;
import org.jnode.fs.jfat.FatDirEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatRootDirectoryBufferTest {

    private static final int SECTOR_SIZE = 512;
    private static final int FAT16_TOT_SEC16 = 40000;
    private static final int FAT16_FAT_SECTORS = 20;
    private static final int FAT16_SEC_PER_CLUSTER = 8;
    private static final int FAT16_CLUSTER_SIZE = FAT16_SEC_PER_CLUSTER * SECTOR_SIZE;
    private static final int BIG_ROOT_ENT_CNT = 5000;
    private static final int ODD_ROOT_ENT_CNT = 999;

    private static final int FAT12_TOT_SEC16 = 720;
    private static final int FAT12_SEC_PER_CLUSTER = 2;
    private static final int FAT12_CLUSTER_SIZE = FAT12_SEC_PER_CLUSTER * SECTOR_SIZE;
    private static final int FAT12_FAT_SECTORS = 2;
    private static final int FAT12_ROOT_ENT_CNT = 33;

    private FileDevice device;
    private CountingFSBlockDeviceAPI countingApi;
    private FileSystemService fss;
    private File tempImage;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }
        fss = FileSystemTestUtils.createFSService(FatFileSystemType.class.getName());
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }
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
        if (tempImage != null && tempImage.exists()) {
            tempImage.delete();
            tempImage = null;
        }
    }

    @Test
    public void testReadsOneDeviceReadPerClusterOfRootEntries() throws Exception {
        tempImage = createFat16Image("jfatroot-percluster", ODD_ROOT_ENT_CNT, false);
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();
            evictClusterBuffer(root);
            countingApi.resetReadStats();

            int entriesPerCluster = FAT16_CLUSTER_SIZE / FatDirEntry.LENGTH;
            for (int i = 0; i < 2 * entriesPerCluster; i++) {
                root.getFatDirEntry(i, true);
            }

            assertEquals("entries are served cluster-at-a-time, not 32 bytes at a time",
                2, countingApi.readCount());
        } finally {
            fs.close();
            device.close();
        }
    }

    @Test
    public void testLargeFat16RootIterationReadsOneDeviceReadPerCluster() throws Exception {
        tempImage = createFat16Image("jfatroot-big", BIG_ROOT_ENT_CNT, true);
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();
            BootSector bs = fs.getBootSector();
            long rootStart = bs.getFirstRootDirSector() * (long) bs.getBytesPerSector();
            long rootRegionBytes = bs.getNrRootDirEntries() * FatDirEntry.LENGTH;

            evictClusterBuffer(root);
            countingApi.resetReadStats();

            int count = 0;
            Iterator<FSEntry> it = root.createIterator(false);
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(BIG_ROOT_ENT_CNT, count);
            long expectedReads = (rootRegionBytes + FAT16_CLUSTER_SIZE - 1) / FAT16_CLUSTER_SIZE;
            assertEquals("an ls of a 5000-entry FAT16 root must not read 5000 sectors",
                expectedReads, countingApi.readCount());
            assertEquals("no read may cover bytes outside the fixed root region",
                rootRegionBytes, countingApi.bytesRead());
            assertTrue("read extends past getNrRootDirEntries()*32 bytes",
                countingApi.maxReadEnd() <= rootStart + rootRegionBytes);
            assertEquals("last (partial) cluster must be clamped to the remaining root region bytes",
                rootRegionBytes - (expectedReads - 1) * FAT16_CLUSTER_SIZE, countingApi.lastReadLength());
        } finally {
            fs.close();
            device.close();
        }
    }

    @Test
    public void testOddRootEntCntLastClusterIsClampedNotOverRead() throws Exception {
        tempImage = createFat16Image("jfatroot-odd", ODD_ROOT_ENT_CNT, false);
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();
            BootSector bs = fs.getBootSector();
            long rootStart = bs.getFirstRootDirSector() * (long) bs.getBytesPerSector();
            long rootRegionBytes = bs.getNrRootDirEntries() * FatDirEntry.LENGTH;
            assertEquals("fixture must be an odd RootEntCnt", 1, ODD_ROOT_ENT_CNT % 2);

            evictClusterBuffer(root);
            countingApi.resetReadStats();

            for (int i = 0; i < ODD_ROOT_ENT_CNT; i++) {
                root.getFatDirEntry(i, true);
            }

            long expectedReads = (rootRegionBytes + FAT16_CLUSTER_SIZE - 1) / FAT16_CLUSTER_SIZE;
            assertEquals(expectedReads, countingApi.readCount());
            assertEquals("no over-read past getNrRootDirEntries()*32 bytes",
                rootRegionBytes, countingApi.bytesRead());
            assertTrue("read extends past the fixed root region",
                countingApi.maxReadEnd() <= rootStart + rootRegionBytes);
            assertEquals("last (partial) cluster must be clamped to the remaining root region bytes",
                rootRegionBytes - (expectedReads - 1) * FAT16_CLUSTER_SIZE, countingApi.lastReadLength());
        } finally {
            fs.close();
            device.close();
        }
    }

    @Test
    public void testFat12OddRootEntCntLastClusterIsClampedNotOverRead() throws Exception {
        tempImage = createFat12Image("jfatroot-fat12", FAT12_ROOT_ENT_CNT);
        FatFileSystem fs = mountRW(tempImage);
        try {
            assertTrue(fs.getBootSector().isFat12());
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();
            BootSector bs = fs.getBootSector();
            long rootStart = bs.getFirstRootDirSector() * (long) bs.getBytesPerSector();
            long rootRegionBytes = bs.getNrRootDirEntries() * FatDirEntry.LENGTH;
            assertEquals("fixture must be an odd RootEntCnt", 1, FAT12_ROOT_ENT_CNT % 2);

            evictClusterBuffer(root);
            countingApi.resetReadStats();

            for (int i = 0; i < FAT12_ROOT_ENT_CNT; i++) {
                root.getFatDirEntry(i, true);
            }

            long expectedReads = (rootRegionBytes + FAT12_CLUSTER_SIZE - 1) / FAT12_CLUSTER_SIZE;
            assertEquals(expectedReads, countingApi.readCount());
            assertEquals("no over-read past getNrRootDirEntries()*32 bytes",
                rootRegionBytes, countingApi.bytesRead());
            assertTrue("read extends past the fixed root region",
                countingApi.maxReadEnd() <= rootStart + rootRegionBytes);
            assertEquals("last (partial) cluster must be clamped to the remaining root region bytes",
                rootRegionBytes - (expectedReads - 1) * FAT12_CLUSTER_SIZE, countingApi.lastReadLength());
        } finally {
            fs.close();
            device.close();
        }
    }

    @Test
    public void testSetFatDirEntryInvalidatesClusterBuffer() throws Exception {
        tempImage = createFat16Image("jfatroot-invalidate", ODD_ROOT_ENT_CNT, true);
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();
            evictClusterBuffer(root);
            countingApi.resetReadStats();

            FatDirEntry entry = root.getFatDirEntry(0, true);
            assertEquals(1, countingApi.readCount());
            root.getFatDirEntry(1, true);
            assertEquals("second read must be served from the cluster buffer", 1, countingApi.readCount());

            root.setFatDirEntry(entry);
            root.getFatDirEntry(0, true);
            assertEquals("write must invalidate the cluster buffer", 2, countingApi.readCount());
        } finally {
            fs.close();
            device.close();
        }
    }

    private void evictClusterBuffer(FatDirectory root) throws IOException {
        FatDirEntry entry = root.getFatDirEntry(0, true);
        root.setFatDirEntry(entry);
    }

    private FatFileSystem mountRW(File file) throws Exception {
        device = new FileDevice(file, "rw");
        countingApi = new CountingFSBlockDeviceAPI(device);
        device.unregisterAPI(FSBlockDeviceAPI.class);
        device.unregisterAPI(BlockDeviceAPI.class);
        device.registerAPI(FSBlockDeviceAPI.class, countingApi);
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, false);
    }

    private static File createFat16Image(String prefix, int rootEntCnt, boolean fillRoot) throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        try {
            fileDevice.setLength((long) FAT16_TOT_SEC16 * SECTOR_SIZE);
            BootSector bs = new BootSector(SECTOR_SIZE);
            bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x3C, (byte) 0x90});
            bs.setBS_OemName("JNODE10");
            bs.setBPB_BytesPerSector(SECTOR_SIZE);
            bs.setBPB_SecPerCluster(FAT16_SEC_PER_CLUSTER);
            bs.setBPB_RsvSecCount(1);
            bs.setBPB_NoFATs(2);
            bs.setBPB_RootEntCnt(rootEntCnt);
            bs.setBPB_TotSec16(FAT16_TOT_SEC16);
            bs.setBPB_MediumDescriptor(0xF8);
            bs.setBPB_FATSz16(FAT16_FAT_SECTORS);
            bs.setBPB_SecPerTrk(63);
            bs.setBPB_NumHeads(255);
            bs.setBPB_HiddSec(0);
            bs.setBPB_TotSec32(0);

            BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
            bs.write(api, 0);

            byte[] fat = new byte[FAT16_FAT_SECTORS * SECTOR_SIZE];
            setFat16Entry(fat, 0, 0xFFF8);
            setFat16Entry(fat, 1, 0xFFFF);
            api.write(SECTOR_SIZE, ByteBuffer.wrap(fat));
            api.write(SECTOR_SIZE + fat.length, ByteBuffer.wrap(fat));

            if (fillRoot) {
                writeRootEntries(api, rootStart(1, 2 * FAT16_FAT_SECTORS), rootEntCnt);
            }
            api.flush();
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }

    private static File createFat12Image(String prefix, int rootEntCnt) throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        try {
            fileDevice.setLength((long) FAT12_TOT_SEC16 * SECTOR_SIZE);
            BootSector bs = new BootSector(SECTOR_SIZE);
            bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x3C, (byte) 0x90});
            bs.setBS_OemName("JNODE10");
            bs.setBPB_BytesPerSector(SECTOR_SIZE);
            bs.setBPB_SecPerCluster(FAT12_SEC_PER_CLUSTER);
            bs.setBPB_RsvSecCount(1);
            bs.setBPB_NoFATs(2);
            bs.setBPB_RootEntCnt(rootEntCnt);
            bs.setBPB_TotSec16(FAT12_TOT_SEC16);
            bs.setBPB_MediumDescriptor(0xF0);
            bs.setBPB_FATSz16(FAT12_FAT_SECTORS);
            bs.setBPB_SecPerTrk(9);
            bs.setBPB_NumHeads(2);
            bs.setBPB_HiddSec(0);
            bs.setBPB_TotSec32(0);

            BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
            bs.write(api, 0);

            byte[] fat = new byte[FAT12_FAT_SECTORS * SECTOR_SIZE];
            Fat12ImageBuilder.setFat12Entry(fat, 0, 0xFF0);
            Fat12ImageBuilder.setFat12Entry(fat, 1, 0xFFF);
            api.write(SECTOR_SIZE, ByteBuffer.wrap(fat));
            api.write(SECTOR_SIZE + fat.length, ByteBuffer.wrap(fat));
            api.flush();
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }

    private static long rootStart(int reservedSectors, int fatSectors) {
        return (reservedSectors + fatSectors) * (long) SECTOR_SIZE;
    }

    private static void writeRootEntries(BlockDeviceAPI api, long start, int count) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(count * FatDirEntry.LENGTH);
        for (int i = 0; i < count; i++) {
            byte[] entry = Fat12ImageBuilder.makeDirEntry(fileName(i), 2, 0);
            buf.put(entry);
        }
        buf.flip();
        api.write(start, buf);
    }

    private static String fileName(int index) {
        return String.format("FILE%04d.TXT", index);
    }

    private static void setFat16Entry(byte[] fat, int index, int value) {
        int offset = 2 * index;
        fat[offset] = (byte) (value & 0xFF);
        fat[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static final class CountingFSBlockDeviceAPI implements FSBlockDeviceAPI {
        private final FileDevice delegate;
        private final AtomicLong readCount = new AtomicLong();
        private final AtomicLong bytesRead = new AtomicLong();
        private volatile long maxReadEnd = -1;
        private volatile long lastReadLength = -1;

        private CountingFSBlockDeviceAPI(FileDevice delegate) {
            this.delegate = delegate;
        }

        public long getLength() throws IOException {
            return delegate.getLength();
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
            int n = dest.remaining();
            readCount.incrementAndGet();
            bytesRead.addAndGet(n);
            long end = devOffset + n;
            if (end > maxReadEnd) {
                maxReadEnd = end;
            }
            lastReadLength = n;
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

        public org.jnode.partitions.PartitionTableEntry getPartitionTableEntry() {
            return delegate.getPartitionTableEntry();
        }

        long readCount() {
            return readCount.get();
        }

        long getAndResetReadCount() {
            return readCount.getAndSet(0);
        }

        void resetReadStats() {
            readCount.set(0);
            bytesRead.set(0);
            maxReadEnd = -1;
        }

        long bytesRead() {
            return bytesRead.get();
        }

        long maxReadEnd() {
            return maxReadEnd;
        }

        long lastReadLength() {
            return lastReadLength;
        }
    }
}
