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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatCopyComparisonTest {

    private FileDevice device;
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
    public void testIdenticalFat12CopiesReportNoDivergenceAndMountRW() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-copies-identical");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat12());
        Fat fat = fs.getFat();
        assertTrue("Identical FAT copies must not be flagged",
            fat.getDivergentFatSectors().isEmpty());
        assertTrue(fat.compareFatCopies().isEmpty());
        assertEquals(3, fat.get(2));
        int old = fat.set(9, 0xFFF);
        assertEquals(0, old);
        assertEquals(0xFFF, fat.get(9));
        fs.flush();
        fs.close();
        device.close();
        fs = mountRO(tempImage);
        assertEquals(0xFFF, fs.getFat().get(9));
        assertTrue(fs.getFat().getDivergentFatSectors().isEmpty());
        fs.close();
        device.close();
    }

    @Test
    public void testDivergentFat12CopyWarnsAndContinuesWithCopy0() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-copies-divergent");
        long fat1First = fatFirstOffset(tempImage, 1);
        corruptByte(tempImage, fat1First + 10, (byte) 0x55);
        FatFileSystem fs = mountRO(tempImage);
        Fat fat = fs.getFat();
        assertNotNull("Mount must continue with copy 0 despite divergence", fs);
        List<Long> divergent = fat.getDivergentFatSectors();
        assertEquals(1, divergent.size());
        assertEquals(Long.valueOf(fat.getFirstSector(1)), divergent.get(0));
        assertEquals(divergent, fat.compareFatCopies());
        assertEquals(3, fat.get(2));
        assertEquals(0xFFF, fat.get(7));
        fs.close();
        device.close();
    }

    @Test
    public void testSingleActiveFat32WritesOnlyActiveFatAndReportsNoDivergence() throws Exception {
        tempImage = Fat32ImageBuilder.createImageFile("jfat32-single-active", 0x0081, true);
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat32());
        assertTrue("BPB_ExtFlags bit 7 must mark the mirror as disabled",
            fs.getBootSector().isFatMirrorDisabled());
        assertEquals("active FAT index must be decoded from bits 0-3",
            1, fs.getBootSector().getActiveFat());
        Fat fat = fs.getFat();
        assertEquals("active FAT index must be exposed on Fat", 1, fat.getActiveFatIndex());
        assertEquals("root cluster must decode from the active FAT (FAT 1), not the filler FAT 0",
            0x0FFFFFFF, fat.get(2));
        assertTrue("comparison is meaningless against a deliberately inactive FAT",
            fat.getDivergentFatSectors().isEmpty());
        assertTrue("compareFatCopies must skip single-active-FAT comparison",
            fat.compareFatCopies().isEmpty());

        int old = fat.set(9, 0x0FFFFFFF);
        assertEquals(0, old);
        fs.flush();
        fs.close();
        device.close();

        byte[] fat0 = readRawFat(tempImage, 0);
        byte[] fat1 = readRawFat(tempImage, 1);
        assertInt32Le(0xAAAAAAAAL, fat0, 9 * 4);
        assertInt32Le(0x0FFFFFFF, fat1, 9 * 4);

        fs = mountRO(tempImage);
        assertEquals("entry written before remount must be visible via the active FAT",
            0x0FFFFFFF, fs.getFat().get(9));
        assertTrue(fs.getFat().getDivergentFatSectors().isEmpty());
        fs.close();
        device.close();
    }

    @Test
    public void testSingleActiveFat32ActiveZeroWritesOnlyFat0() throws Exception {
        tempImage = Fat32ImageBuilder.createImageFile("jfat32-active-zero", 0x0080, false);
        byte[] fat1Before = readRawFat(tempImage, 1);
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFatMirrorDisabled());
        assertEquals(0, fs.getBootSector().getActiveFat());
        Fat fat = fs.getFat();
        assertEquals("active FAT 0 must decode entry 2", 0x0FFFFFFF, fat.get(2));
        fat.set(9, 0x0FFFFFFF);
        fs.flush();
        fs.close();
        device.close();

        byte[] fat0 = readRawFat(tempImage, 0);
        byte[] fat1 = readRawFat(tempImage, 1);
        assertInt32Le(0x0FFFFFFF, fat0, 9 * 4);
        assertArrayEquals("FAT 1 must stay byte-identical when FAT 0 is the single active FAT",
            fat1Before, fat1);
    }

    @Test
    public void testMirroredFat32WritesReachAllCopiesUnchanged() throws Exception {
        tempImage = Fat32ImageBuilder.createImageFile("jfat32-mirrored", 0x0000, false);
        FatFileSystem fs = mountRW(tempImage);
        assertFalse(fs.getBootSector().isFatMirrorDisabled());
        assertEquals(0, fs.getBootSector().getActiveFat());
        Fat fat = fs.getFat();
        assertTrue("identical mirrored copies must not be flagged",
            fat.getDivergentFatSectors().isEmpty());

        int old = fat.set(9, 0x0FFFFFFF);
        assertEquals(0, old);
        fs.flush();
        fs.close();
        device.close();

        assertInt32Le(0x0FFFFFFF, readRawFat(tempImage, 0), 9 * 4);
        assertInt32Le(0x0FFFFFFF, readRawFat(tempImage, 1), 9 * 4);
    }

    @Test
    public void testDivergentFat16CopyWarnsAndContinuesWithCopy0() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-copies-divergent");
        long fat1First = fatFirstOffset(tempImage, 1);
        corruptByte(tempImage, fat1First + 10, (byte) 0xA5);
        FatFileSystem fs = mountRO(tempImage);
        assertTrue(fs.getBootSector().isFat16());
        Fat fat = fs.getFat();
        List<Long> divergent = fat.getDivergentFatSectors();
        assertEquals(1, divergent.size());
        assertEquals(Long.valueOf(fat.getFirstSector(1)), divergent.get(0));
        assertNotNull(fs.getRootEntry().getDirectory().getEntry("test.txt"));
        fs.close();
        device.close();
    }

    private byte[] readRawFat(File file, int fatNum) throws Exception {
        FileDevice probe = new FileDevice(file, "r");
        try {
            BlockDeviceAPI api = probe.getAPI(BlockDeviceAPI.class);
            BootSector bs = new BootSector(512);
            bs.read(api);
            long fatSectors = bs.getSectorsPerFat();
            long firstSector = (long) bs.getNrReservedSectors() + fatSectors * fatNum;
            byte[] fat = new byte[(int) fatSectors * bs.getBytesPerSector()];
            api.read(firstSector * bs.getBytesPerSector(), ByteBuffer.wrap(fat));
            return fat;
        } finally {
            probe.close();
        }
    }

    private static void assertInt32Le(long expected, byte[] raw, int offset) {
        long actual = ((long) raw[offset] & 0xFF)
            | (((long) raw[offset + 1] & 0xFF) << 8)
            | (((long) raw[offset + 2] & 0xFF) << 16)
            | (((long) raw[offset + 3] & 0xFF) << 24);
        assertEquals("32-bit LE word at offset " + offset, expected, actual);
    }

    private FatFileSystem mountRW(File file) throws Exception {
        device = new FileDevice(file, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, false);
    }

    private FatFileSystem mountRO(File file) throws Exception {
        device = new FileDevice(file, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, true);
    }

    private long fatFirstOffset(File file, int fatNum) throws Exception {
        FileDevice probe = new FileDevice(file, "r");
        try {
            BlockDeviceAPI api = probe.getAPI(BlockDeviceAPI.class);
            BootSector bs = new BootSector(512);
            bs.read(api);
            long sector = (long) bs.getNrReservedSectors() + bs.getSectorsPerFat() * fatNum;
            return sector * bs.getBytesPerSector();
        } finally {
            probe.close();
        }
    }

    private void corruptByte(File file, long offset, byte value) throws Exception {
        FileDevice probe = new FileDevice(file, "rw");
        try {
            BlockDeviceAPI api = probe.getAPI(BlockDeviceAPI.class);
            byte[] original = new byte[1];
            api.read(offset, ByteBuffer.wrap(original));
            byte flipped = (byte) (original[0] ^ 0xFF);
            if (flipped == value) {
                flipped = (byte) (flipped ^ 0x0F);
            }
            api.write(offset, ByteBuffer.wrap(new byte[]{flipped}));
            api.flush();
        } finally {
            probe.close();
        }
    }

    private File copyFixture(String fixture, String prefix) throws Exception {
        File source = FileSystemTestUtils.getTestFile(fixture);
        File copy = File.createTempFile(prefix, ".img");
        copy.deleteOnExit();
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(copy);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return copy;
    }
}
