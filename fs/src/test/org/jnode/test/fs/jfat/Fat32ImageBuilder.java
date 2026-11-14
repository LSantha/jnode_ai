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
import java.nio.ByteBuffer;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.fs.jfat.BootSector;
import org.jnode.util.LittleEndian;

/**
 * Builds a minimal hand-crafted FAT32 image for the single-active-FAT
 * (BPB_ExtFlags bit 7) tests. Geometry:
 *
 * <pre>
 * BPB_BytsPerSec  = 512
 * BPB_SecPerClus  = 8            (4096-byte clusters)
 * BPB_RsvdSecCnt  = 32
 * BPB_NumFATs     = 2
 * BPB_RootEntCnt  = 0            (FAT32)
 * BPB_TotSec32    = 4096
 * BPB_FATSz16     = 0            (FAT32)
 * BPB_FATSz32     = 8 sectors
 * BPB_RootClus    = 2
 * </pre>
 *
 * <p>Derived layout: FirstDataSector = 32 + 2*8 = 48; cluster 2 at sector
 * 48; DataSec = 4096 - 48 = 4048; CountOfClusters = 506. The image file is
 * 4096 sectors (2 MiB). FAT entry 9 is free (0) in the canonical FATs so
 * tests can allocate it.</p>
 *
 * <p>When {@code fillFat0WithGarbage} is true, FAT copy 0 is filled with
 * 0xAA bytes (entry 2 does not read as the root-cluster EOF marker under
 * copy-0 decoding) and FAT copy 1 carries the canonical content
 * (entry0 = 0xFFFFFFF8, entry1 = 0xFFFFFFFF, entry2 = 0x0FFFFFFF). That
 * shape is only mountable when JFAT honours the active FAT index. When it
 * is false, both FATs are canonical and byte-identical (mirrored volume).</p>
 */
final class Fat32ImageBuilder {

    static final int BYTES_PER_SECTOR = 512;
    static final int SEC_PER_CLUSTER = 8;
    static final int RESERVED_SECTORS = 32;
    static final int FAT_SECTORS = 8;
    static final int TOTAL_SECTORS = 4096;
    static final int FAT0_FIRST_SECTOR = RESERVED_SECTORS;
    static final int FAT1_FIRST_SECTOR = RESERVED_SECTORS + FAT_SECTORS;
    static final int FIRST_DATA_SECTOR = RESERVED_SECTORS + 2 * FAT_SECTORS;
    static final int ROOT_CLUSTER = 2;

    static final long FAT0_FIRST_BYTE = (long) FAT0_FIRST_SECTOR * BYTES_PER_SECTOR;
    static final long FAT1_FIRST_BYTE = (long) FAT1_FIRST_SECTOR * BYTES_PER_SECTOR;

    private Fat32ImageBuilder() {
    }

    static File createImageFile(String prefix, int extFlags, boolean fillFat0WithGarbage)
        throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        try {
            fileDevice.setLength((long) TOTAL_SECTORS * BYTES_PER_SECTOR);

            BootSector bs = new BootSector(512);
            bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x58, (byte) 0x90});
            bs.setBS_OemName("JNODE10");
            bs.setBPB_BytesPerSector(BYTES_PER_SECTOR);
            bs.setBPB_SecPerCluster(SEC_PER_CLUSTER);
            bs.setBPB_RsvSecCount(RESERVED_SECTORS);
            bs.setBPB_NoFATs(2);
            bs.setBPB_RootEntCnt(0);
            bs.setBPB_TotSec16(0);
            bs.setBPB_MediumDescriptor(0xF8);
            bs.setBPB_FATSz16(0);
            bs.setBPB_SecPerTrk(63);
            bs.setBPB_NumHeads(255);
            bs.setBPB_HiddSec(0);
            bs.setBPB_TotSec32(TOTAL_SECTORS);
            bs.setBPB_FATSz32(FAT_SECTORS);
            bs.setBPB_ExtFlags(extFlags);
            bs.setBPB_FSVer(0);
            bs.setBPB_RootClus(ROOT_CLUSTER);
            bs.setBPB_FSInfo(1);
            bs.setBPB_BkBootSec(6);
            bs.setBPB_Reserved(new byte[12]);
            bs.setBS_DrvNum(0x80);
            bs.setBS_Reserved1(0);
            bs.setBS_BootSig(0x29);
            bs.setBS_VolID(0x12345678L);
            bs.setBS_VolLab("SINGLEACT ");
            bs.setBS_FilSysType("FAT32   ");
            bs.setBS_Identifier(new byte[]{0x55, (byte) 0xAA});

            BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
            bs.write(api, 0);

            byte[] fat1 = canonicalFat();
            byte[] fat0 = fillFat0WithGarbage ? garbageFat() : canonicalFat();
            api.write(FAT0_FIRST_BYTE, ByteBuffer.wrap(fat0));
            api.write(FAT1_FIRST_BYTE, ByteBuffer.wrap(fat1));

            byte[] root = new byte[SEC_PER_CLUSTER * BYTES_PER_SECTOR];
            api.write((long) FIRST_DATA_SECTOR * BYTES_PER_SECTOR, ByteBuffer.wrap(root));
            api.flush();
        } finally {
            fileDevice.close();
        }
        return tempFile;
    }

    private static byte[] canonicalFat() {
        byte[] fat = new byte[FAT_SECTORS * BYTES_PER_SECTOR];
        setFat32Entry(fat, 0, 0x0FFFFFF8);
        setFat32Entry(fat, 1, 0x0FFFFFFF);
        setFat32Entry(fat, ROOT_CLUSTER, 0x0FFFFFFF);
        return fat;
    }

    private static byte[] garbageFat() {
        byte[] fat = new byte[FAT_SECTORS * BYTES_PER_SECTOR];
        for (int i = 0; i < fat.length; i++) {
            fat[i] = (byte) 0xAA;
        }
        return fat;
    }

    private static void setFat32Entry(byte[] fat, int index, int value) {
        LittleEndian.setInt32(fat, 4 * index, value);
    }
}
