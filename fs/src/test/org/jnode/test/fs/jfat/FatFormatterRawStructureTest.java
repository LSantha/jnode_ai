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
import java.io.RandomAccessFile;
import java.util.Arrays;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;

/**
 * Raw-byte regression net for {@code FatFormatter}'s on-disk output: every other
 * JFAT test either reads a pre-made image or re-mounts a freshly formatted one
 * through the same code, so a wrong on-disk field that JFAT itself ignores is
 * invisible. This test formats blank images and reads the boot sector, FSInfo
 * sector and both FAT copies back with {@code RandomAccessFile}, asserting the
 * exact bytes - against constants and against an oracle computed from the BPB,
 * never against the formatter's own accessors.
 * <p>
 * It is the regression net for {@code 00-bugs/r01} ({@code FSI_Free_Count} was
 * wrong on every volume JFAT had ever formatted and nothing noticed) and for the
 * FSInfo policy from ticket #898: the formatter writes {@code FSI_Free_Count}
 * and {@code FSI_Nxt_Free} as {@code 0xFFFFFFFF} ("unknown") and never maintains
 * them. A future change that starts writing a count must be a deliberate policy
 * change caught here, not an accident.
 * <p>
 * Covers {@link ClusterSize#_1Kb} and {@link ClusterSize#_4Kb}; both the primary
 * structures (sectors 0 and 1) and the backups (sectors 6 and 7, located via
 * {@code BPB_BkBootSec} from the primary BPB) are verified, as is FAT copy 1
 * against FAT copy 0.
 *
 * @see FatFormatterRoundTripTest
 * @see <a href="https://github.com/LSantha/jnode_ai/issues/934">ticket #934</a>
 */
public class FatFormatterRawStructureTest {

    /** 68 MiB: comfortably above the formatter's enforced 64K-sector FAT32 floor. */
    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;

    private static final int SECTOR_SIZE = 512;

    /** Values passed to (or fixed by) the formatter, per ticket #934. */
    private static final int RESERVED_SECTOR_COUNT = 32;
    private static final int NUM_FATS = 2;
    private static final int ROOT_ENT_CNT = 0;
    private static final int HIDDEN_SECTORS = 0;
    private static final int MEDIA_DESCRIPTOR = 0xf8; // FatFormatter.HD_DESC
    private static final int BK_BOOT_SEC = 6;

    private FileSystemService fss;
    private File diskFile;
    private FileDevice fileDevice;

    @org.junit.Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
            // already initialized by a previous test in this JVM
        }
        fss = FileSystemTestUtils.createFSService(FatFileSystemType.class.getName());
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }
        diskFile = File.createTempFile("jfat-raw-structure", ".img");
        diskFile.deleteOnExit();
    }

    @org.junit.After
    public void tearDown() throws Exception {
        closeQuietly(fileDevice);
        fileDevice = null;
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    // ------------------------------------------------------------ BPB (sector 0)

    @org.junit.Test
    public void testRawBootSector_1Kb() throws Exception {
        checkBootSector(ClusterSize._1Kb);
    }

    @org.junit.Test
    public void testRawBootSector_4Kb() throws Exception {
        checkBootSector(ClusterSize._4Kb);
    }

    private void checkBootSector(ClusterSize clusterSize) throws Exception {
        formatAndClose(clusterSize);
        RandomAccessFile raf = openImage();
        try {
            byte[] boot = readSector(raf, 0);
            int sectorsPerCluster = clusterSize.getSize() / SECTOR_SIZE;
            long totalSectors = IMAGE_LENGTH / SECTOR_SIZE;
            long fatSz32 = expectedFatSizeSectors(totalSectors, sectorsPerCluster);
            String cs = clusterSize.toString();

            assertBytes("BS_jmpBoot", cs, boot, 0, new byte[] {(byte) 0xeb, 0x5a, (byte) 0x90});
            assertBytes("BS_OEMName", cs, boot, 3, ascii("MSWIN4.1"));
            assertLe16("BPB_BytsPerSec", cs, boot, 11, 512);
            assertByte("BPB_SecPerClus", cs, boot[13], sectorsPerCluster);
            assertLe16("BPB_RsvdSecCnt", cs, boot, 14, RESERVED_SECTOR_COUNT);
            assertByte("BPB_NumFATs", cs, boot[16], NUM_FATS);
            assertLe16("BPB_RootEntCnt", cs, boot, 17, ROOT_ENT_CNT);
            assertLe16("BPB_TotSec16", cs, boot, 19, 0);
            assertByte("BPB_Media", cs, boot[21], MEDIA_DESCRIPTOR);
            assertLe16("BPB_FATSz16", cs, boot, 22, 0);
            assertLe16("BPB_SecPerTrk", cs, boot, 24, 63);
            assertLe16("BPB_NumHeads", cs, boot, 26, 255);
            assertLe32("BPB_HiddSec", cs, boot, 28, HIDDEN_SECTORS);
            assertLe32("BPB_TotSec32", cs, boot, 32, totalSectors);
            assertLe32("BPB_FATSz32", cs, boot, 36, fatSz32);
            assertLe16("BPB_ExtFlags", cs, boot, 40, 0);
            assertLe16("BPB_FSVer", cs, boot, 42, 0);
            assertLe32("BPB_RootClus", cs, boot, 44, 2);
            assertLe16("BPB_FSInfo", cs, boot, 48, 1);
            assertLe16("BPB_BkBootSec", cs, boot, 50, BK_BOOT_SEC);
            assertByte("BS_DrvNum", cs, boot[64], 0x80);
            assertByte("BS_Reserved1", cs, boot[65], 0);
            assertByte("BS_BootSig", cs, boot[66], 0x29);
            assertBytes("BS_VolLab", cs, boot, 71, ascii("NO NAME    "));
            assertBytes("BS_FilSysType", cs, boot, 82, ascii("FAT32   "));
            assertBytes("BS_BootSignature", cs, boot, 510, new byte[] {0x55, (byte) 0xaa});
        } finally {
            raf.close();
        }
    }

    // --------------------------------------------------- FSInfo (sectors 1 and 7)

    @org.junit.Test
    public void testRawFsInfo_1Kb() throws Exception {
        checkFsInfo(ClusterSize._1Kb);
    }

    @org.junit.Test
    public void testRawFsInfo_4Kb() throws Exception {
        checkFsInfo(ClusterSize._4Kb);
    }

    private void checkFsInfo(ClusterSize clusterSize) throws Exception {
        formatAndClose(clusterSize);
        RandomAccessFile raf = openImage();
        try {
            byte[] primaryBoot = readSector(raf, 0);
            int bkBootSec = (int) readLe16(primaryBoot, 50);
            for (int sector : new int[] {1, bkBootSec + 1}) {
                byte[] fsInfo = readSector(raf, sector);
                String cs = clusterSize.toString();
                String where = "FSInfo sector " + sector + " (" + cs + ")";
                assertLe32("FSI_LeadSig (" + where + ")", cs, fsInfo, 0, 0x41615252L);
                assertAllZero("FSI_Reserved1 (" + where + ")", cs, fsInfo, 4, 480);
                assertLe32("FSI_StrucSig (" + where + ")", cs, fsInfo, 484, 0x61417272L);
                assertLe32("FSI_Free_Count (" + where + ")", cs, fsInfo, 488, 0xffffffffL);
                assertLe32("FSI_Nxt_Free (" + where + ")", cs, fsInfo, 492, 0xffffffffL);
                assertAllZero("FSI_Reserved2 (" + where + ")", cs, fsInfo, 496, 12);
                assertLe32("FSI_TrailSig (" + where + ")", cs, fsInfo, 508, 0xaa550000L);
            }
        } finally {
            raf.close();
        }
    }

    // -------------------------------------- backup structures vs. the primary ones

    @org.junit.Test
    public void testBackupBootSectorAndFsInfoMatchPrimary_1Kb() throws Exception {
        checkBackupMatchesPrimary(ClusterSize._1Kb);
    }

    @org.junit.Test
    public void testBackupBootSectorAndFsInfoMatchPrimary_4Kb() throws Exception {
        checkBackupMatchesPrimary(ClusterSize._4Kb);
    }

    private void checkBackupMatchesPrimary(ClusterSize clusterSize) throws Exception {
        formatAndClose(clusterSize);
        RandomAccessFile raf = openImage();
        try {
            byte[] primaryBoot = readSector(raf, 0);
            int bkBootSec = (int) readLe16(primaryBoot, 50);
            byte[] backupBoot = readSector(raf, bkBootSec);
            byte[] primaryFsInfo = readSector(raf, 1);
            byte[] backupFsInfo = readSector(raf, bkBootSec + 1);
            String cs = clusterSize.toString();
            org.junit.Assert.assertArrayEquals(
                "backup boot sector (sector " + bkBootSec + ", " + cs + ") must be byte-identical to the primary",
                primaryBoot, backupBoot);
            org.junit.Assert.assertArrayEquals(
                "backup FSInfo sector (sector " + (bkBootSec + 1) + ", " + cs
                    + ") must be byte-identical to the primary",
                primaryFsInfo, backupFsInfo);
        } finally {
            raf.close();
        }
    }

    // ------------------------------------------------------------------ FAT copies

    @org.junit.Test
    public void testFatReservedEntriesAndMatchingCopies_1Kb() throws Exception {
        checkFats(ClusterSize._1Kb);
    }

    @org.junit.Test
    public void testFatReservedEntriesAndMatchingCopies_4Kb() throws Exception {
        checkFats(ClusterSize._4Kb);
    }

    private void checkFats(ClusterSize clusterSize) throws Exception {
        formatAndClose(clusterSize);
        RandomAccessFile raf = openImage();
        try {
            byte[] primaryBoot = readSector(raf, 0);
            String cs = clusterSize.toString();
            int sectorsPerCluster = clusterSize.getSize() / SECTOR_SIZE;
            int reservedSectors = (int) readLe16(primaryBoot, 14);
            long totalSectors = readLe32(primaryBoot, 32);
            long fatSz32 = readLe32(primaryBoot, 36);
            org.junit.Assert.assertEquals("BPB_FATSz32 (" + cs + ")",
                expectedFatSizeSectors(totalSectors, sectorsPerCluster), fatSz32);

            long fat0Offset = (long) reservedSectors * SECTOR_SIZE;
            long fat1Offset = fat0Offset + fatSz32 * SECTOR_SIZE;
            byte[] fat0FirstSector = readSector(raf, reservedSectors);

            // The FAT32 spec's reserved entries: entry 0 is the media descriptor
            // (0xf8 for a hard disk) with the reserved high bits, entry 1 is
            // end-of-chain, entry 2 (the root directory cluster) is end-of-chain.
            assertLe32("FAT[0] (" + cs + ")", cs, fat0FirstSector, 0, 0x0ffffff8L);
            assertLe32("FAT[1] (" + cs + ")", cs, fat0FirstSector, 4, 0xffffffffL);
            assertLe32("FAT[2] (" + cs + ")", cs, fat0FirstSector, 8, 0x0fffffffL);

            // FAT copy 1 must be byte-identical to FAT copy 0.
            byte[] fat0 = new byte[(int) (fatSz32 * SECTOR_SIZE)];
            raf.seek(fat0Offset);
            raf.readFully(fat0);
            byte[] fat1 = new byte[(int) (fatSz32 * SECTOR_SIZE)];
            raf.seek(fat1Offset);
            raf.readFully(fat1);
            org.junit.Assert.assertEquals("FAT copy 1 must be byte-identical to FAT copy 0 (" + cs + ")",
                fat0.length, fat1.length);
            org.junit.Assert.assertTrue("FAT copy 1 must be byte-identical to FAT copy 0 (" + cs + ")",
                Arrays.equals(fat0, fat1));
        } finally {
            raf.close();
        }
    }

    // -------------------------------------------------------------------- helpers

    /**
     * Independent re-derivation of the formatter's FAT-size formula
     * ({@code FatFormatter.getFATSizeSectors}), from the Microsoft FAT spec:
     * {@code FATSz = (4 * (totSec - rsvdSecCnt)) / (secPerClus * bytesPerSec + 4 * numFATs) + 1}.
     */
    private static long expectedFatSizeSectors(long totalSectors, long sectorsPerCluster) {
        long numerator = 4L * (totalSectors - RESERVED_SECTOR_COUNT);
        long denominator = sectorsPerCluster * SECTOR_SIZE + 4L * NUM_FATS;
        return numerator / denominator + 1;
    }

    private void formatAndClose(ClusterSize clusterSize) throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_LENGTH);
        FatFileSystem fs = new FatFileSystemFormatter(clusterSize).format(fileDevice);
        // format(Device) returns a live filesystem; close it (and the device) so the
        // raw bytes on disk are fully flushed before we read them.
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;
    }

    private RandomAccessFile openImage() throws Exception {
        return new RandomAccessFile(diskFile, "r");
    }

    private static byte[] readSector(RandomAccessFile raf, long sector) throws Exception {
        byte[] buf = new byte[SECTOR_SIZE];
        raf.seek(sector * (long) SECTOR_SIZE);
        raf.readFully(buf);
        return buf;
    }

    private static long readLe16(byte[] buf, int offset) {
        return (buf[offset] & 0xffL) | ((buf[offset + 1] & 0xffL) << 8);
    }

    private static long readLe32(byte[] buf, int offset) {
        return (buf[offset] & 0xffL) | ((buf[offset + 1] & 0xffL) << 8)
            | ((buf[offset + 2] & 0xffL) << 16) | ((buf[offset + 3] & 0xffL) << 24);
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    private static void assertBytes(String what, String cs, byte[] actual, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (actual[offset + i] != expected[i]) {
                org.junit.Assert.fail(what + " byte " + i + " (" + cs + "): expected 0x"
                    + Integer.toHexString(expected[i] & 0xff) + " but was 0x"
                    + Integer.toHexString(actual[offset + i] & 0xff));
            }
        }
    }

    private static void assertAllZero(String what, String cs, byte[] actual, int offset, int length) {
        for (int i = 0; i < length; i++) {
            if (actual[offset + i] != 0) {
                org.junit.Assert.fail(what + " byte " + i + " (" + cs + "): expected 0x00 but was 0x"
                    + Integer.toHexString(actual[offset + i] & 0xff));
            }
        }
    }

    private static void assertByte(String what, String cs, byte actual, int expected) {
        org.junit.Assert.assertEquals(what + " (" + cs + ")", expected & 0xff, actual & 0xff);
    }

    private static void assertLe16(String what, String cs, byte[] buf, int offset, long expected) {
        org.junit.Assert.assertEquals(what + " (" + cs + ")", expected, readLe16(buf, offset));
    }

    private static void assertLe32(String what, String cs, byte[] buf, int offset, long expected) {
        org.junit.Assert.assertEquals(what + " (" + cs + ")", expected, readLe32(buf, offset));
    }

    private void closeQuietly(FileDevice device) {
        if (device == null) {
            return;
        }
        try {
            device.close();
        } catch (Exception e) {
            // best effort
        }
    }
}
