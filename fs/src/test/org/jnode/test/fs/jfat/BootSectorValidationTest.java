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
import java.io.IOException;

import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.Fat;
import org.jnode.test.fs.FileSystemTestUtils;
import org.jnode.util.LittleEndian;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Mount-time validation of impossible BPB shapes. Every rejection must be a
 * {@link FileSystemException} naming the offending field and value, never a
 * bare {@code ArithmeticException}.
 */
public class BootSectorValidationTest {

    private static byte[] validFat12Sector() {
        byte[] sector = new byte[512];
        sector[0] = (byte) 0xEB;
        sector[1] = 0x3C;
        sector[2] = (byte) 0x90;
        LittleEndian.setInt16(sector, 11, 512);
        LittleEndian.setInt8(sector, 13, 2);
        LittleEndian.setInt16(sector, 14, 1);
        sector[16] = 2;
        LittleEndian.setInt16(sector, 17, 224);
        LittleEndian.setInt16(sector, 19, 720);
        sector[21] = (byte) 0xF0;
        LittleEndian.setInt16(sector, 22, 2);
        LittleEndian.setInt16(sector, 24, 9);
        LittleEndian.setInt16(sector, 26, 2);
        LittleEndian.setInt32(sector, 28, 0);
        LittleEndian.setInt32(sector, 32, 0);
        sector[510] = 0x55;
        sector[511] = (byte) 0xAA;
        return sector;
    }

    private static byte[] validFat32Sector() {
        byte[] sector = new byte[512];
        sector[0] = (byte) 0xEB;
        sector[1] = 0x3C;
        sector[2] = (byte) 0x90;
        LittleEndian.setInt16(sector, 11, 512);
        LittleEndian.setInt8(sector, 13, 2);
        LittleEndian.setInt16(sector, 14, 36);
        sector[16] = 2;
        LittleEndian.setInt16(sector, 17, 0);
        LittleEndian.setInt16(sector, 19, 0);
        sector[21] = (byte) 0xF8;
        LittleEndian.setInt16(sector, 22, 0);
        LittleEndian.setInt16(sector, 24, 63);
        LittleEndian.setInt16(sector, 26, 255);
        LittleEndian.setInt32(sector, 28, 0);
        LittleEndian.setInt32(sector, 32, 204800);
        LittleEndian.setInt32(sector, 36, 794);
        LittleEndian.setInt16(sector, 42, 0);
        LittleEndian.setInt32(sector, 44, 2);
        sector[510] = 0x55;
        sector[511] = (byte) 0xAA;
        return sector;
    }

    private static FileSystemException expectFailure(byte[] sector, String field, String value) {
        try {
            new BootSector(sector);
            fail("expected FileSystemException naming " + field);
            return null;
        } catch (FileSystemException e) {
            String message = e.getMessage();
            assertTrue("message should name " + field + ": " + message,
                message != null && message.contains(field));
            assertTrue("message should name the value " + value + ": " + message,
                message != null && message.contains(value));
            return e;
        }
    }

    @Test
    public void testZeroedBootSectorFails() {
        expectFailure(new byte[512], "BPB_BytsPerSec", "0");
    }

    @Test
    public void testInvalidBytesPerSector() {
        int[] rejected = { 0, 256, 300, 1000, 8192 };
        for (int bps : rejected) {
            byte[] sector = validFat12Sector();
            LittleEndian.setInt16(sector, 11, bps);
            expectFailure(sector, "BPB_BytsPerSec", String.valueOf(bps));
        }
    }

    @Test
    public void testSecPerClusterZero() {
        byte[] sector = validFat12Sector();
        LittleEndian.setInt8(sector, 13, 0);
        expectFailure(sector, "BPB_SecPerClus", "0");
    }

    @Test
    public void testSecPerClusterNotPowerOfTwo() {
        int[] rejected = { 3, 5, 6, 7, 17, 24 };
        for (int spc : rejected) {
            byte[] sector = validFat12Sector();
            LittleEndian.setInt8(sector, 13, spc);
            expectFailure(sector, "BPB_SecPerClus", String.valueOf(spc));
        }
    }

    @Test
    public void testNumFatsZero() {
        byte[] sector = validFat12Sector();
        sector[16] = 0;
        expectFailure(sector, "BPB_NumFATs", "0");
    }

    @Test
    public void testFat16WithZeroFatSz16() {
        byte[] sector = validFat12Sector();
        LittleEndian.setInt16(sector, 22, 0);
        LittleEndian.setInt32(sector, 36, 7);
        expectFailure(sector, "BPB_FATSz16", "0");
    }

    @Test
    public void testFat32WithZeroFatSz32() {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt32(sector, 36, 0);
        expectFailure(sector, "BPB_FATSz32", "0");
    }

    @Test
    public void testRootEntCntZeroOnNonFat32() {
        byte[] sector = validFat12Sector();
        LittleEndian.setInt16(sector, 17, 0);
        expectFailure(sector, "BPB_RootEntCnt", "0");
    }

    @Test
    public void testMediaF0AndF8ToFFAccepted() throws Exception {
        int[] accepted = { 0xF0, 0xF8, 0xF9, 0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF };
        for (int media : accepted) {
            byte[] sector = validFat12Sector();
            sector[21] = (byte) media;
            new BootSector(sector);
        }
    }

    @Test
    public void testMediaOutsideF0AndF8ToFFRejected() {
        int[] rejected = { 0x00, 0xEF, 0xF1, 0xF5, 0xF7 };
        for (int media : rejected) {
            byte[] sector = validFat12Sector();
            sector[21] = (byte) media;
            expectFailure(sector, "BPB_Media", String.valueOf(media));
        }
    }

    @Test
    public void testFat32FsVerZeroAccepted() throws Exception {
        BootSector bs = new BootSector(validFat32Sector());
        assertTrue("Should be FAT32", bs.isFat32());
    }

    @Test
    public void testFat32FsVerNonZeroRejected() {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt16(sector, 42, 0x1234);
        expectFailure(sector, "BPB_FSVer", "4660");
    }

    @Test
    public void testFat32RootClusZeroRejected() {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt32(sector, 44, 0);
        expectFailure(sector, "BPB_RootClus", "0");
    }

    @Test
    public void testFat32RootClusOneRejected() {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt32(sector, 44, 1);
        expectFailure(sector, "BPB_RootClus", "1");
    }

    @Test
    public void testFat32RootClusAboveCountOfClustersPlusOneRejected() {
        // Synthetic sector: TotSec32=204800, RsvdSecCnt=36, 2 FATs of 794 sectors,
        // SecPerClus=2 -> CountOfClusters = (204800 - 1624) / 2 = 101588,
        // so the highest valid cluster number is 101589.
        byte[] sector = validFat32Sector();
        LittleEndian.setInt32(sector, 44, 101590);
        expectFailure(sector, "BPB_RootClus", "101590");
    }

    @Test
    public void testFat32RootClusCountOfClustersPlusOneAccepted() throws Exception {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt32(sector, 44, 101589);
        BootSector bs = new BootSector(sector);
        assertTrue("Should be FAT32", bs.isFat32());
    }

    @Test
    public void testAllFiveFixturesDecode() throws Exception {
        String[] names = {
            "test.fat16",
            "test.fat32",
            "test.mkfsfat12",
            "test.mkfsfat16label",
            "test.mkfsfat16nolabel"
        };
        for (String name : names) {
            byte[] sector = readFixtureBootSector(name);
            BootSector bs = new BootSector(sector);
            if ("test.fat32".equals(name)) {
                assertTrue("test.fat32 should be FAT32", bs.isFat32());
            } else {
                assertTrue(name + " should not be FAT32", !bs.isFat32());
            }
        }
    }

    private static byte[] readFixtureBootSector(String name) throws IOException {
        File file = FileSystemTestUtils.getTestFile("test/fs/jfat/" + name);
        byte[] sector = new byte[512];
        FileInputStream in = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < sector.length) {
                int n = in.read(sector, offset, sector.length - offset);
                if (n < 0) {
                    break;
                }
                offset += n;
            }
            if (offset != sector.length) {
                throw new IOException("Short read of fixture boot sector " + name + ": " + offset);
            }
        } finally {
            in.close();
        }
        return sector;
    }

    @Test
    public void testTotSecSmallerThanFirstDataSector() {
        byte[] sector = validFat12Sector();
        LittleEndian.setInt16(sector, 19, 10);
        expectFailure(sector, "TotSec", "10");
    }

    @Test
    public void testValidFat12GeometryStillMounts() throws Exception {
        BootSector bs = new BootSector(validFat12Sector());
        assertTrue("Should be FAT12", bs.isFat12());
        assertTrue("Should not be FAT32", !bs.isFat32());
    }

    @Test
    public void testValidFat32GeometryStillMounts() throws Exception {
        BootSector bs = new BootSector(validFat32Sector());
        assertTrue("Should be FAT32", bs.isFat32());
        assertTrue("Should not be FAT12", !bs.isFat12());
    }

    @Test
    public void testValidFat32WithExtFlagsSingleActiveFatMounts() throws Exception {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt16(sector, 40, 0x0081);
        BootSector bs = new BootSector(sector);
        assertTrue("Should be FAT32", bs.isFat32());
        assertTrue("bit 7 must disable the mirror", bs.isFatMirrorDisabled());
        assertEquals("active FAT must come from bits 0-3", 1, bs.getActiveFat());
        assertEquals("getExtFlags must return the raw field", 0x0081, bs.getExtFlags());
    }

    @Test
    public void testFat32ExtFlagsSingleActiveFatZeroMounts() throws Exception {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt16(sector, 40, 0x0080);
        BootSector bs = new BootSector(sector);
        assertTrue("bit 7 must disable the mirror", bs.isFatMirrorDisabled());
        assertEquals("active FAT 0 must be honoured", 0, bs.getActiveFat());
    }

    @Test
    public void testFat32ExtFlagsActiveFatBeyondNumFatsFails() {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt16(sector, 40, 0x0080 | 2);
        expectFailure(sector, "BPB_ExtFlags", "2");
    }

    @Test
    public void testFat32ExtFlagsMirroredIgnoresActiveIndex() throws Exception {
        byte[] sector = validFat32Sector();
        LittleEndian.setInt16(sector, 40, 0x0001);
        BootSector bs = new BootSector(sector);
        assertTrue("bit 7 clear must report a mirrored volume", !bs.isFatMirrorDisabled());
        assertEquals("mirrored volume must report active FAT 0", 0, bs.getActiveFat());
    }

    @Test
    public void testFat12IgnoresExtFlagsBytes() throws Exception {
        byte[] sector = validFat12Sector();
        sector[40] = (byte) 0x81;
        sector[41] = 0x00;
        BootSector bs = new BootSector(sector);
        assertTrue("FAT12 must not expose BPB_ExtFlags", !bs.isFatMirrorDisabled());
        assertEquals("FAT12 must report active FAT 0", 0, bs.getActiveFat());
        assertEquals("FAT12 must report raw ExtFlags 0", 0, bs.getExtFlags());
    }

    @Test
    public void testZeroedBootSectorFailsMountAsFileSystemException() throws Exception {
        File file = File.createTempFile("jfat-zero", ".img");
        file.deleteOnExit();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[512]);
        } finally {
            out.close();
        }
        FileDevice device = new FileDevice(file, "r");
        try {
            Fat.create(device);
            fail("expected FileSystemException");
        } catch (FileSystemException e) {
            String message = e.getMessage();
            assertTrue("message should name BPB_BytsPerSec: " + message,
                message != null && message.contains("BPB_BytsPerSec"));
        } finally {
            device.close();
        }
    }
}
