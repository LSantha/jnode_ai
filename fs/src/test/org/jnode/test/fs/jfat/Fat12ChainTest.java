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

package org.jnode.test.fs.jfat;

import java.io.File;
import java.nio.ByteBuffer;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Read-only FAT12 chain/packing coverage for the shared
 * {@link Fat12ImageBuilder} fixture. See the builder for full geometry
 * documentation (BPB fields, FAT count/size, root-directory sectors,
 * 12-bit packing). {@code Fat12.set()} is unimplemented upstream, so
 * allocation/free/write cases are intentionally absent; EOF assertions
 * avoid the 0xFF0-0xFF7 boundary where the implementation threshold
 * (0xFF0) differs from the spec (0xFF8).
 */
public class Fat12ChainTest {

    private FileDevice device;
    private FileDevice fat16Device;
    private FileSystemService fss;
    private File tempFile;
    private File fat16TempFile;
    private FatFileSystem fs;

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
        tempFile = Fat12ImageBuilder.createImageFile("jfat12-chain");
        device = new FileDevice(tempFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        fs = type.create(device, true);
    }

    @After
    public void tearDown() throws Exception {
        if (fs != null) {
            try {
                fs.close();
            } catch (Exception e) {
            }
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
            }
        }
        if (fat16Device != null) {
            try {
                fat16Device.close();
            } catch (Exception e) {
            }
        }
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
        if (fat16TempFile != null && fat16TempFile.exists()) {
            fat16TempFile.delete();
        }
    }

    private Fat fat() {
        return fs.getFat();
    }

    @Test
    public void testBootSectorGeometry() throws Exception {
        BootSector bs = fs.getBootSector();
        assertTrue("Should be FAT12", bs.isFat12());
        assertFalse("Should not be FAT16", bs.isFat16());
        assertFalse("Should not be FAT32", bs.isFat32());
        assertEquals("Bytes per sector", 512, bs.getBytesPerSector());
        assertEquals("Sectors per cluster", 2, bs.getSectorsPerCluster());
        assertEquals("Number of FATs", 2, bs.getNrFats());
        assertEquals("Root dir entries", 224, bs.getNrRootDirEntries());
        assertEquals("FAT size sectors", 2, bs.getSectorsPerFat());
        assertEquals("Count of clusters", 350, bs.getCountOfClusters());
        assertEquals("First data sector", 19, bs.getFirstDataSector());
        assertEquals("Cluster size", 1024, fs.getClusterSize());
        assertEquals("Fat size", 352, fat().size());
        assertEquals("Media descriptor", 0xF0, bs.getMediumDescriptor());
    }

    @Test
    public void testReservedEntries() throws Exception {
        assertEquals("FAT[0] media", 0xFF0, fat().get(0));
        assertEquals("FAT[1] reserved", 0xFFF, fat().get(1));
    }

    @Test
    public void testEvenEntryPacking() throws Exception {
        assertEquals("FAT[2]", 3, fat().get(2));
        assertEquals("FAT[4] EOF", 0xFFF, fat().get(4));
        assertEquals("FAT[340]", 341, fat().get(340));
        assertEquals("FAT[342] EOF", 0xFFF, fat().get(342));
    }

    @Test
    public void testOddEntryPacking() throws Exception {
        assertEquals("FAT[3]", 4, fat().get(3));
        assertEquals("FAT[5] EOF", 0xFFF, fat().get(5));
        assertEquals("FAT[341]", 342, fat().get(341));
    }

    @Test
    public void test512ByteStraddleAndRawBytes() throws Exception {
        // Entry 341 at offset floor(1.5*341)=511: its two bytes are 511,512,
        // the last byte of FAT sector 1 and first byte of FAT sector 2.
        assertEquals("FAT[341] straddle value", 342, fat().get(341));
        byte[] raw = readRawFat();
        assertEquals("raw FAT[511] middle byte", 0x61, raw[511] & 0xFF);
        assertEquals("raw FAT[512] top byte of odd entry", 0x15, raw[512] & 0xFF);
        assertEquals("FAT[340] even neighbour", 341, fat().get(340));
        assertEquals("FAT[341] odd straddling entry", 342, fat().get(341));
        assertEquals("FAT[342] following entry", 0xFFF, fat().get(342));
    }

    @Test
    public void testLowChainFollowing() throws Exception {
        FatChain chain = new FatChain(fs, 2);
        assertEquals("Chain(2) size", 3, chain.size());
        FatChain.ChainIterator it = chain.listIterator(0);
        assertTrue(it.hasNext());
        assertEquals("chain[0]", 2, it.next());
        assertEquals("chain[1]", 3, it.next());
        assertEquals("chain[2]", 4, it.next());
        assertFalse(it.hasNext());
        assertEquals("FAT[4] EOF marker", 0xFFF, fat().get(4));
        assertFalse(fat().hasNext(fat().get(4)));
        assertTrue(fat().hasNext(fat().get(2)));
    }

    @Test
    public void testStraddleChainFollowing() throws Exception {
        FatChain chain = new FatChain(fs, Fat12ImageBuilder.STRADDLE_HEAD);
        assertEquals("Straddle chain size", 3, chain.size());
        FatChain.ChainIterator it = chain.listIterator(0);
        assertEquals("chain[0]", 340, it.next());
        assertEquals("chain[1]", 341, it.next());
        assertEquals("chain[2]", 342, it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testSingleClusterChain() throws Exception {
        FatChain chain = new FatChain(fs, Fat12ImageBuilder.SINGLE_START_CLUSTER);
        assertEquals("Single chain size", 1, chain.size());
        FatChain.ChainIterator it = chain.listIterator(0);
        assertTrue(it.hasNext());
        assertEquals("chain[0]", 5, it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testFreeEntries() throws Exception {
        assertEquals("FAT[6] free", 0, fat().get(6));
        assertEquals("FAT[343] free", 0, fat().get(343));
        assertTrue("6 is free", fat().isFreeEntry(6));
        assertTrue("343 is free", fat().isFreeEntry(343));
        assertFalse("2 is allocated", fat().isFreeEntry(2));
    }

    @Test
    public void testUnambiguousEofMarkers() throws Exception {
        // 0xFF8 and 0xFFF are EOF under both the impl threshold (0xFF0) and the
        // spec (0xFF8); 0xFEF/0x123/0x000 are not. The 0xFF0-0xFF7 boundary is
        // a known impl/spec divergence and is deliberately not pinned here.
        assertTrue("0xFF8 EOF", fat().isEofChain(0xFF8));
        assertTrue("0xFFF EOF", fat().isEofChain(0xFFF));
        assertFalse("0xFEF not EOF", fat().isEofChain(0xFEF));
        assertFalse("0x123 not EOF", fat().isEofChain(0x123));
        assertFalse("0x000 not EOF", fat().isEofChain(0x000));
        assertFalse("hasNext(0xFFF)", fat().hasNext(0xFFF));
        assertFalse("hasNext(0xFF8)", fat().hasNext(0xFF8));
        assertTrue("hasNext(4)", fat().hasNext(4));
        assertFalse("hasNext(0)", fat().hasNext(0));
    }

    @Test
    public void testRootDirectoryEntries() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry multi = root.getEntry(Fat12ImageBuilder.MULTI_NAME);
        assertNotNull("MULTI.TXT should exist", multi);
        assertTrue(multi.isFile());
        assertEquals("MULTI.TXT length", Fat12ImageBuilder.MULTI_LENGTH, multi.getFile().getLength());

        FSEntry single = root.getEntry(Fat12ImageBuilder.SINGLE_NAME);
        assertNotNull("SINGLE.TXT should exist", single);
        assertTrue(single.isFile());
        assertEquals("SINGLE.TXT length", Fat12ImageBuilder.SINGLE_LENGTH, single.getFile().getLength());
    }

    @Test
    public void testMultiClusterFileReadByteIdentical() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry(Fat12ImageBuilder.MULTI_NAME);
        assertNotNull(entry);
        FSFile file = entry.getFile();
        int length = Fat12ImageBuilder.MULTI_LENGTH;
        assertEquals(length, file.getLength());
        byte[] expected = Fat12ImageBuilder.expectedContent(length);
        ByteBuffer buf = ByteBuffer.allocate(length);
        file.read(0, buf);
        buf.flip();
        byte[] actual = new byte[buf.remaining()];
        buf.get(actual);
        assertEquals("read length", length, actual.length);
        assertArrayEquals("MULTI.TXT bytes", expected, actual);
        // Spot-check values spanning all three clusters (1024-byte clusters).
        assertEquals("byte 0", (byte) 0, actual[0]);
        assertEquals("byte 1023 end cluster2", (byte) 0xFF, actual[1023]);
        assertEquals("byte 1024 start cluster3", (byte) 0, actual[1024]);
        assertEquals("byte 2499 last", (byte) (2499 & 0xFF), actual[2499]);
    }

    @Test
    public void testSingleClusterFileRead() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry(Fat12ImageBuilder.SINGLE_NAME);
        assertNotNull(entry);
        FSFile file = entry.getFile();
        int length = Fat12ImageBuilder.SINGLE_LENGTH;
        ByteBuffer buf = ByteBuffer.allocate(length);
        file.read(0, buf);
        buf.flip();
        byte[] actual = new byte[buf.remaining()];
        buf.get(actual);
        assertArrayEquals("SINGLE.TXT bytes",
                Fat12ImageBuilder.expectedContent(length), actual);
    }

    @Test
    public void testChainReadMatchesFileRead() throws Exception {
        FatChain chain = new FatChain(fs, Fat12ImageBuilder.MULTI_START_CLUSTER);
        int length = Fat12ImageBuilder.MULTI_LENGTH;
        ByteBuffer buf = ByteBuffer.allocate(length);
        chain.read(0, buf);
        buf.flip();
        byte[] actual = new byte[buf.remaining()];
        buf.get(actual);
        assertArrayEquals("FatChain read bytes",
                Fat12ImageBuilder.expectedContent(length), actual);
    }

    @Test
    public void testFat16CrossCheckSameChainSemantics() throws Exception {
        // Same logical chain (2->3->4->EOF) packed as 16-bit entries:
        // packing width differs, chain semantics are identical.
        fat16TempFile = createFat16ImageFile("jfat16-xcheck");
        fat16Device = new FileDevice(fat16TempFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs16 = type.create(fat16Device, true);
        try {
            BootSector bs = fs16.getBootSector();
            assertTrue("Should be FAT16", bs.isFat16());
            assertFalse("Should not be FAT12", bs.isFat12());
            Fat fat16 = fs16.getFat();
            assertEquals("FAT16[0]", 0xFFF8, fat16.get(0));
            assertEquals("FAT16[1]", 0xFFFF, fat16.get(1));
            assertEquals("FAT16[2]", 3, fat16.get(2));
            assertEquals("FAT16[3]", 4, fat16.get(3));
            assertEquals("FAT16[4] EOF", 0xFFFF, fat16.get(4));
            assertTrue("FAT16 EOF 0xFFF8", fat16.isEofChain(0xFFF8));
            assertTrue("FAT16 EOF 0xFFFF", fat16.isEofChain(0xFFFF));

            FatChain chain = new FatChain(fs16, 2);
            assertEquals("FAT16 chain size", 3, chain.size());
            FatChain.ChainIterator it = chain.listIterator(0);
            assertEquals("FAT16 chain[0]", 2, it.next());
            assertEquals("FAT16 chain[1]", 3, it.next());
            assertEquals("FAT16 chain[2]", 4, it.next());
            assertFalse(it.hasNext());

            // The FAT12 image decodes the same small chain identically.
            assertEquals("FAT12[2] matches FAT16[2]", fat16.get(2), fat().get(2));
            assertEquals("FAT12[3] matches FAT16[3]", fat16.get(3), fat().get(3));
        } finally {
            fs16.close();
        }
        fat16Device.close();
        fat16Device = null;
    }

    private byte[] readRawFat() throws Exception {
        BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);
        byte[] raw = new byte[Fat12ImageBuilder.FAT_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(raw);
        api.read(512, buf);
        return raw;
    }

    /** Minimal FAT16 image: same 2/3/4 chain, 16-bit packed, 8192-byte FAT. */
    private static File createFat16ImageFile(String prefix) throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        try {
            fileDevice.setLength(20000L * 512);
            BootSector bs = new BootSector(512);
            bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x3C, (byte) 0x90});
            bs.setBS_OemName("JNODE10");
            bs.setBPB_BytesPerSector(512);
            bs.setBPB_SecPerCluster(2);
            bs.setBPB_RsvSecCount(1);
            bs.setBPB_NoFATs(2);
            bs.setBPB_RootEntCnt(224);
            bs.setBPB_TotSec16(20000);
            bs.setBPB_MediumDescriptor(0xF8);
            bs.setBPB_FATSz16(16);
            bs.setBPB_SecPerTrk(63);
            bs.setBPB_NumHeads(255);
            bs.setBPB_HiddSec(0);
            bs.setBPB_TotSec32(0);

            BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
            bs.write(api, 0);

            byte[] fat = new byte[16 * 512];
            setFat16Entry(fat, 0, 0xFFF8);
            setFat16Entry(fat, 1, 0xFFFF);
            setFat16Entry(fat, 2, 3);
            setFat16Entry(fat, 3, 4);
            setFat16Entry(fat, 4, 0xFFFF);
            api.write(512, ByteBuffer.wrap(fat));
            api.write(512 + fat.length, ByteBuffer.wrap(fat));
            api.flush();
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }

    private static void setFat16Entry(byte[] fat, int index, int value) {
        int offset = 2 * index;
        fat[offset] = (byte) (value & 0xFF);
        fat[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
