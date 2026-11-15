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
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.FatDirectory;
import org.jnode.fs.jfat.FatDirEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatShortDirEntry;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins the entry-ownership decision for directory scans: scanned entries own
 * their 32 bytes, so they stay correct after the directory's cluster buffer is
 * recycled (by eviction and by a rescan), and a rescan reads the image again
 * rather than serving stale bytes from a recycled scratch.
 */
public class FatScanOwnershipTest {

    private static final int SECTOR_SIZE = 512;
    private static final int FAT16_TOT_SEC16 = 40000;
    private static final int FAT16_FAT_SECTORS = 20;
    private static final int FAT16_SEC_PER_CLUSTER = 8;
    private static final int FAT16_CLUSTER_SIZE = FAT16_SEC_PER_CLUSTER * SECTOR_SIZE;
    private static final int ROOT_ENT_CNT = 256;
    private static final int FILLED_ENTRIES = 200;

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
    public void testScannedEntriesSurviveClusterBufferEviction() throws Exception {
        tempImage = createFat16Image("jfatscan-evict");
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();

            FatShortDirEntry[] first = new FatShortDirEntry[FILLED_ENTRIES];
            byte[][] firstRaw = new byte[FILLED_ENTRIES][];
            for (int i = 0; i < FILLED_ENTRIES; i++) {
                first[i] = (FatShortDirEntry) root.getFatDirEntry(i, true);
                firstRaw[i] = snapshot(first[i]);
            }

            /*
             * Force the cluster buffer to be recycled: a write invalidates it,
             * and the following scan must re-read the image.
             */
            root.setFatDirEntry(first[0]);
            countingApi.resetReadStats();

            for (int i = 0; i < FILLED_ENTRIES; i++) {
                FatShortDirEntry second = (FatShortDirEntry) root.getFatDirEntry(i, true);
                assertArrayEquals("entry " + i + " raw bytes changed across recycle",
                    firstRaw[i], snapshot(second));
                assertEquals("entry " + i + " base", first[i].getBase(), second.getBase());
                assertEquals("entry " + i + " ext", first[i].getExt(), second.getExt());
                assertEquals("entry " + i + " cluster",
                    first[i].getStartCluster(), second.getStartCluster());
                assertEquals("entry " + i + " length", first[i].getLength(), second.getLength());
                assertEquals("entry " + i + " created", first[i].getCreated(), second.getCreated());
                assertEquals("entry " + i + " accessed",
                    first[i].getLastAccessed(), second.getLastAccessed());
                assertEquals("entry " + i + " modified",
                    first[i].getLastModified(), second.getLastModified());
                assertEquals("entry " + i + " chksum", first[i].getChkSum(), second.getChkSum());
                assertEquals("entry " + i + " archive", first[i].isArchive(), second.isArchive());
            }

            assertTrue("the rescan after eviction must read the image again",
                countingApi.readCount() >= 1);

            /*
             * Entries handed out by the first scan must still read their own
             * bytes, not the recycled cluster buffer.
             */
            for (int i = 0; i < FILLED_ENTRIES; i++) {
                assertArrayEquals("first-scan entry " + i + " lost its bytes after recycle",
                    firstRaw[i], snapshot(first[i]));
                assertEquals("first-scan entry " + i + " base",
                    first[i].getShortName(),
                    "FILE" + String.format("%04d", i) + ".TXT");
            }
        } finally {
            fs.close();
            device.close();
        }
    }

    @Test
    public void testRepeatedScansReturnStableResults() throws Exception {
        tempImage = createFat16Image("jfatscan-repeat");
        FatFileSystem fs = mountRW(tempImage);
        try {
            FatDirectory root = (FatDirectory) fs.getRootEntry().getDirectory();

            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < FILLED_ENTRIES; i++) {
                    FatShortDirEntry entry = (FatShortDirEntry) root.getFatDirEntry(i, true);
                    assertEquals("round " + round + " entry " + i,
                        "FILE" + String.format("%04d", i) + ".TXT", entry.getShortName());
                    assertEquals("round " + round + " entry " + i + " cluster",
                        2, entry.getStartCluster());
                    assertTrue("round " + round + " entry " + i + " archive",
                        entry.isArchive());
                }
            }
        } finally {
            fs.close();
            device.close();
        }
    }

    private static byte[] snapshot(FatDirEntry entry) {
        byte[] src = entry.getByteBuffer().array();
        return Arrays.copyOf(src, src.length);
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

    private static File createFat16Image(String prefix) throws Exception {
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
            bs.setBPB_RootEntCnt(ROOT_ENT_CNT);
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

            long rootStart = (1 + 2L * FAT16_FAT_SECTORS) * SECTOR_SIZE;
            writeRootEntries(api, rootStart, FILLED_ENTRIES);
            api.flush();
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }

    private static void writeRootEntries(BlockDeviceAPI api, long start, int count)
            throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(count * FatDirEntry.LENGTH);
        for (int i = 0; i < count; i++) {
            byte[] entry = Fat12ImageBuilder.makeDirEntry(
                "FILE" + String.format("%04d", i) + ".TXT", 2, 4096);
            buf.put(entry);
        }
        buf.flip();
        api.write(start, buf);
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

        private CountingFSBlockDeviceAPI(FileDevice delegate) {
            this.delegate = delegate;
        }

        public long getLength() throws IOException {
            return delegate.getLength();
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
            readCount.incrementAndGet();
            bytesRead.addAndGet(dest.remaining());
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

        void resetReadStats() {
            readCount.set(0);
            bytesRead.set(0);
        }
    }
}
