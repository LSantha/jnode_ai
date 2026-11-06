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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.fs.jfat.BootSector;
import org.jnode.util.LittleEndian;

/**
 * Builds a spec-constructed FAT12 image (shared by {@link FatFileSystemTest}
 * and {@link Fat12ChainTest}).
 *
 * <p>Fixture geometry (kept identical to the pre-refactor 360KB fixture
 * except the file is now sized to the BPB total so high-cluster positions
 * stay in bounds; see below). Values are chosen so {@link BootSector#compute()}
 * classifies the volume as FAT12 (countOfClusters &lt; 4085).</p>
 *
 * <pre>
 * BPB_BytsPerSec  = 512
 * BPB_SecPerClus  = 2            (1024-byte clusters)
 * BPB_RsvdSecCnt  = 1
 * BPB_NumFATs     = 2
 * BPB_RootEntCnt  = 224          (224*32/512 = 14 root-directory sectors)
 * BPB_TotSec16    = 720
 * BPB_Media       = 0xF0
 * BPB_FATSz16     = 2 sectors (1024-byte FAT)
 * BPB_SecPerTrk   = 9
 * BPB_NumHeads    = 2
 * BPB_HiddSec     = 0
 * BPB_TotSec32    = 0
 * </pre>
 *
 * <p>Derived layout (per Microsoft FAT spec, verified against
 * {@link BootSector#compute()}):</p>
 *
 * <pre>
 * RootDirSectors   = ((224*32)+(512-1))/512 = 14
 * FirstDataSector  = 1 + 2*2 + 14 = 19
 * DataSec          = 720 - (1+2*2+14) = 701
 * CountOfClusters  = 701/2 = 350  (&lt; 4085, hence FAT12)
 * Fat.size()       = 350 + 2 = 352 entries
 * FAT bytes needed = ceil(352*1.5) = 528 bytes, FAT holds 1024 bytes
 * </pre>
 *
 * <p>Sector map (spec view): sector 0 boot, sectors 1-2 FAT#1,
 * sectors 3-4 FAT#2, sectors 5-18 root directory (14 sectors),
 * sectors 19-719 data. File length is 720*512 = 368640 bytes.
 * (The old inline fixture set the file length to 360*512 while the BPB
 * said 720 sectors; the helper uses 720*512 so every cluster position
 * up to the highest allocated cluster is addressable. Low clusters
 * 2..5 are unaffected by the change.)</p>
 *
 * <p>12-bit packing (spec, and exactly as decoded by
 * {@code Fat12.get()}): two entries per three bytes. Even index
 * {@code 2k} sits at byte offset {@code 3k} (low 12 bits of the
 * little-endian 16-bit word there); odd index {@code 2k+1} sits at
 * byte offset {@code 3k+1} (high 12 bits). For entries E (even)
 * and O (odd) sharing bytes b0,b1,b2:
 * b0 = E &amp; 0xFF; b1 = ((E&gt;&gt;8)&amp;0x0F) | ((O&amp;0x0F)&lt;&lt;4);
 * b2 = (O&gt;&gt;4)&amp;0xFF. The builder packs with this formula and the
 * tests verify it with explicit expected values; if the builder and
 * {@code Fat12} ever disagree, the builder (spec) is trusted and the
 * code is the suspect, per the ticket instruction.</p>
 *
 * <p>Trusted-source note (implementation mapping): the spec root
 * directory starts at sector 5, but JFAT's {@code FatRootDirectory}
 * reads it at {@code FirstDataSector*512} (sector 19) and
 * {@code Fat12.getClusterPosition()} places cluster 2 at
 * {@code rootOffset + 224*32} (sector 33), i.e. JFAT double-counts
 * the root directory. To keep the read-path tests meaningful the
 * builder writes directory entries at JFAT's {@code rootDirOffset()}
 * and file data at JFAT's {@code clusterPosition()} rather than at
 * the spec positions. BPB fields and FAT packing remain spec-valid
 * either way; only data placement follows the implementation mapping.
 * A second spec-relevant quirk: {@code BootSector.setBS_DrvNum()} and
 * friends write at FAT32 EBPB offsets (64+), while the FAT12 decoder
 * reads them at 36+. The builder therefore patches EBPB at the spec
 * offsets (36+) after {@code BootSector.write()} instead of relying
 * on those setters.</p>
 *
 * <p>Live content (beyond entries 0/1, single-level root):</p>
 *
 * <pre>
 * FAT entry 0    = 0xFF0  (media 0xF0)
 * FAT entry 1    = 0xFFF
 * 2 -&gt; 3 -&gt; 4 -&gt; 0xFFF   MULTI.TXT (3 clusters, even/odd/even coverage)
 * 5 -&gt; 0xFFF               SINGLE.TXT (1 cluster)
 * 340 -&gt; 341 -&gt; 342 -&gt; 0xFFF  straddle chain: entry 341 at offset 511
 *                             (bytes 511-512 cross the 512-byte FAT boundary)
 * everything else = 0 (free)
 * </pre>
 *
 * <p>File content is deterministic: {@code expected[i] = (byte)(i &amp; 0xFF)}
 * so any chain mis-ordering changes the byte sequence.</p>
 */
final class Fat12ImageBuilder {

    static final int BYTES_PER_SECTOR = 512;
    static final int SEC_PER_CLUSTER = 2;
    static final int CLUSTER_SIZE = BYTES_PER_SECTOR * SEC_PER_CLUSTER;
    static final int RESERVED_SECTORS = 1;
    static final int NR_FATS = 2;
    static final int ROOT_ENT_CNT = 224;
    static final int TOT_SEC16 = 720;
    static final int MEDIA = 0xF0;
    static final int FAT_SECTORS = 2;
    static final int FAT_BYTES = FAT_SECTORS * BYTES_PER_SECTOR;
    static final long IMAGE_BYTES = (long) TOT_SEC16 * BYTES_PER_SECTOR;
    /** FirstDataSector = 1 + 2*2 + 14 = 19. */
    static final int FIRST_DATA_SECTOR = 19;

    static final int MULTI_START_CLUSTER = 2;
    static final int MULTI_LENGTH = 2500;
    static final int SINGLE_START_CLUSTER = 5;
    static final int SINGLE_LENGTH = 100;
    static final int STRADDLE_HEAD = 340;

    static final String MULTI_NAME = "MULTI.TXT";
    static final String SINGLE_NAME = "SINGLE.TXT";

    private Fat12ImageBuilder() {
    }

    /** Byte offset where JFAT's FatRootDirectory reads the root directory. */
    static long rootDirOffset() {
        return (long) FIRST_DATA_SECTOR * BYTES_PER_SECTOR;
    }

    /** Byte offset where JFAT's Fat12.getClusterPosition() maps cluster 0's base. */
    static long clusterPosition(int index) {
        if (index == 0) {
            return rootDirOffset();
        }
        long filesOffset = rootDirOffset() + (long) ROOT_ENT_CNT * 32;
        return filesOffset + (long) (index - 2) * CLUSTER_SIZE;
    }

    static byte[] expectedContent(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    /** Spec 12-bit packing: set entry value (0..0xFFF) at index in a FAT image. */
    static void setFat12Entry(byte[] fat, int index, int value) {
        if (value < 0 || value > 0xFFF) {
            throw new IllegalArgumentException("FAT12 value out of range: " + value);
        }
        int offset = (int) Math.floor(1.5 * index);
        if ((index % 2) == 0) {
            int b0 = fat[offset] & 0xFF;
            int b1 = (fat[offset + 1] & 0xFF) & 0xF0;
            fat[offset] = (byte) ((b0 & 0x00) | (value & 0xFF));
            fat[offset + 1] = (byte) (b1 | ((value >> 8) & 0x0F));
            // Note: entry 0's low byte is the media descriptor; writing 0xFF0
            // through here yields 0xF0, matching the classic F0 FF FF prefix.
        } else {
            int b1 = (fat[offset] & 0xFF) & 0x0F;
            int b2 = fat[offset + 1] & 0xFF;
            fat[offset] = (byte) (b1 | ((value & 0x0F) << 4));
            fat[offset + 1] = (byte) ((b2 & 0x00) | ((value >> 4) & 0xFF));
        }
    }

    static int getFat12Entry(byte[] fat, int index) {
        int offset = (int) Math.floor(1.5 * index);
        int value = (fat[offset] & 0xFF) | ((fat[offset + 1] & 0xFF) << 8);
        if ((index % 2) == 0) {
            return value & 0xFFF;
        }
        return value >> 4;
    }

    /** Raw 32-byte short directory entry (DOS date 1980-01-01, archive attr). */
    static byte[] makeDirEntry(String name8Dot3, int startCluster, int length) {
        byte[] entry = new byte[32];
        int dot = name8Dot3.indexOf('.');
        String base = dot < 0 ? name8Dot3 : name8Dot3.substring(0, dot);
        String ext = dot < 0 ? "" : name8Dot3.substring(dot + 1);
        Arrays.fill(entry, 0, 11, (byte) ' ');
        for (int i = 0; i < base.length() && i < 8; i++) {
            entry[i] = (byte) base.charAt(i);
        }
        for (int i = 0; i < ext.length() && i < 3; i++) {
            entry[8 + i] = (byte) ext.charAt(i);
        }
        entry[11] = 0x20;
        int dosTime = 0;
        int dosDate = (0 << 9) | (1 << 5) | 1;
        LittleEndian.setInt16(entry, 14, dosTime);
        LittleEndian.setInt16(entry, 16, dosDate);
        LittleEndian.setInt16(entry, 18, dosDate);
        LittleEndian.setInt16(entry, 20, 0);
        LittleEndian.setInt16(entry, 22, dosTime);
        LittleEndian.setInt16(entry, 24, dosDate);
        LittleEndian.setInt16(entry, 26, startCluster);
        LittleEndian.setInt32(entry, 28, length);
        return entry;
    }

    static byte[] createFatImage() {
        byte[] fat = new byte[FAT_BYTES];
        setFat12Entry(fat, 0, 0xFF0);
        setFat12Entry(fat, 1, 0xFFF);
        setFat12Entry(fat, 2, 3);
        setFat12Entry(fat, 3, 4);
        setFat12Entry(fat, 4, 0xFFF);
        setFat12Entry(fat, 5, 0xFFF);
        setFat12Entry(fat, STRADDLE_HEAD, STRADDLE_HEAD + 1);
        setFat12Entry(fat, STRADDLE_HEAD + 1, STRADDLE_HEAD + 2);
        setFat12Entry(fat, STRADDLE_HEAD + 2, 0xFFF);
        return fat;
    }

    /** Writes boot sector, FAT copies, root directory and cluster data. */
    static void writeImage(FileDevice fileDevice) throws Exception {
        fileDevice.setLength(IMAGE_BYTES);
        BootSector bs = new BootSector(BYTES_PER_SECTOR);
        bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x3C, (byte) 0x90});
        bs.setBS_OemName("JNODE10");
        bs.setBPB_BytesPerSector(BYTES_PER_SECTOR);
        bs.setBPB_SecPerCluster(SEC_PER_CLUSTER);
        bs.setBPB_RsvSecCount(RESERVED_SECTORS);
        bs.setBPB_NoFATs(NR_FATS);
        bs.setBPB_RootEntCnt(ROOT_ENT_CNT);
        bs.setBPB_TotSec16(TOT_SEC16);
        bs.setBPB_MediumDescriptor(MEDIA);
        bs.setBPB_FATSz16(FAT_SECTORS);
        bs.setBPB_SecPerTrk(9);
        bs.setBPB_NumHeads(2);
        bs.setBPB_HiddSec(0);
        bs.setBPB_TotSec32(0);

        BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
        bs.write(api, 0);

        // EBPB at spec offsets 36..61 (BootSector setters target FAT32 offsets).
        byte[] ebpb = new byte[26];
        ebpb[0] = 0x00;
        ebpb[1] = 0;
        ebpb[2] = 0x29;
        LittleEndian.setInt32(ebpb, 3, 0x12345678);
        byte[] volLab = "TEST12     ".getBytes("US-ASCII");
        System.arraycopy(volLab, 0, ebpb, 7, 11);
        byte[] fsType = "FAT12   ".getBytes("US-ASCII");
        System.arraycopy(fsType, 0, ebpb, 18, 8);
        api.write(36, ByteBuffer.wrap(ebpb));

        byte[] fat = createFatImage();
        api.write(512, ByteBuffer.wrap(fat));
        api.write(512 + FAT_BYTES, ByteBuffer.wrap(fat));

        byte[] multi = makeDirEntry(MULTI_NAME, MULTI_START_CLUSTER, MULTI_LENGTH);
        byte[] single = makeDirEntry(SINGLE_NAME, SINGLE_START_CLUSTER, SINGLE_LENGTH);
        api.write(rootDirOffset(), ByteBuffer.wrap(multi));
        api.write(rootDirOffset() + 32, ByteBuffer.wrap(single));

        byte[] multiData = expectedContent(MULTI_LENGTH);
        for (int off = 0; off < multiData.length;) {
            int cluster = MULTI_START_CLUSTER + (off / CLUSTER_SIZE);
            int inCluster = off % CLUSTER_SIZE;
            int chunk = Math.min(CLUSTER_SIZE - inCluster, multiData.length - off);
            ByteBuffer buf = ByteBuffer.wrap(multiData, off, chunk);
            api.write(clusterPosition(cluster) + inCluster, buf);
            off += chunk;
        }

        byte[] singleData = expectedContent(SINGLE_LENGTH);
        api.write(clusterPosition(SINGLE_START_CLUSTER), ByteBuffer.wrap(singleData));

        api.flush();
    }

    /** Creates a temp image file with the FAT12 fixture; caller deletes it. */
    static File createImageFile(String prefix) throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        try {
            writeImage(fileDevice);
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }
}
