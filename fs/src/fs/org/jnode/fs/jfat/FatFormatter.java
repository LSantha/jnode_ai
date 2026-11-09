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

package org.jnode.fs.jfat;
/*
 * $Id: FatFormatter.java  2007-07-26 +0100 (s,26 JULY 2007) Tanmoy Deb $
 *
 * JNode.org
 * Copyright (C) 2003-2006 JNode.org
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

import static java.lang.Integer.toHexString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;

import org.apache.log4j.Logger;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.util.LittleEndian;

/**
 * <p>
 * According to the FAT32 Documents.
 * <ul>
 * <li>FAT32 disk structure:</li>
 * <li>Sector 0 Boot Sector</li>
 * <li>Sector 1 FSInfo</li>
 * <li>Sector 2 More boot code</li>
 * <li>Sector 3 unused</li>
 * <li>Sector 4 unused</li>
 * <li>Sector 5 unused</li>
 * <li>Sector 6 Backup boot sector</li>
 * <li>Sector 7 Backup FSInfo sector</li>
 * <li>Sector 8 Backup 'more boot code</li>
 * <li>Reserved sectors up to the FAT at sector 32</li>
 * </ul>
 *
 * @author tango
 *
 */
public class FatFormatter {
    private static final Logger log = Logger.getLogger(FatFormatter.class);

    /** The Device Identifier for Floppy Device */
    public static final int FLOPPY_DESC = 0xf0;

    /** The Device Identifier for Hard Disk Device */
    public static final int HD_DESC = 0xf8;

    /** The Device Identifier for RAM Disk Device */
    public static final int RAMDISK_DESC = 0xfa;

    /** The Size of Fat. */
    private int FatSize;

    /** The Number of the Fat in system. */
    public final int NumOfFATs = 2;

    /** The boot sector backing up. */
    public final int BackupBootSector = 6;

    /** The number of reserved sectors. */
    public final int ReservedSectorCount = 32;

    /** The Tracks Per cylinder. */
    private static final int NB_HEADS = 255;

    /** The sector per track. */
    private static final int SECTOR_PER_TRACK = 63;

    /** The volume label. */
    private static final String VOL_LABEL = "NO NAME    ";

    /** The FAT version label. */
    private static final String FAT_LABEL = "FAT32   ";

    /** The Identifier of the Boot Sector */
    public final byte[] BS_Identifier = {(byte) 0x55, (byte) 0xAA};

    /** The First 3 Bytes of the BootSector */
    public final byte[] BS_jmpBoot = {(byte) 0xEB, (byte) 0x5A, (byte) 0x90};

    /**
     * This lead signature is used to validate that this is in fact an FSInfo
     * sector.
     */
    private final int FSI_LeadSig = 0x41615252;

    /**
     * The signature that is more localized in the sector to the location of the
     * fields that are used.
     */
    private final int FSI_StrucSig = 0x61417272;

    /**
     * Contains the last known free cluster count on the volume. If the value is
     * 0xFFFFFFFF, then the free count is unknown and must be computed.
     */
    private int FSI_FreeCount = 0xffffffff;

    /**
     * This is a hint for the FAT driver. It indicates the cluster number at
     * which the driver should start looking for free clusters.
     */
    private final int FSI_Nxt_Free = 3; // A confusion here but OK now for
                                        // setting the Info at 3rd sec
    /**
     * This trail signature is used to validate that this is in fact an FSInfo
     * sector
     */
    private final int FSI_TrailSig = 0xaa550000;

    /** The media ID at the reserved cluster one. */
    private final int ReservedSector_0 = 0x0ffffff8;

    /** The End Of Chain ID at the reserved sector second. */
    private final int ReservedSector_1 = 0xffffffff;

    /** The End of cluster chain of the root directory. */
    private final int ReservedSector_2 = 0x0fffffff;

    /** The array for the reserved sector. */
    private byte[] reservedSector;

    /**
     * The Hard Disk's formatting logic implementation by JFAT. This Version
     * only support to the Hard Disks.
     *
     * @throws IOException
     */
    public static FatFormatter HDDFormatter(int sectorSize, int nbTotalSectors,
            ClusterSize clusterSize, int hiddenSectors, BlockDeviceAPI api) throws IOException {

        return new FatFormatter(HD_DESC, sectorSize, nbTotalSectors, SECTOR_PER_TRACK, NB_HEADS,
                clusterSize, hiddenSectors, api);
    }

    /**
     * The Constructor for the HDD devices in the JNode system.
     *
     * @throws IOException
     *
     */
    public FatFormatter(int mediumDescriptor, int sectorSize, int nbTotalSectors,
            int sectorsPerTrack, int nbHeads, ClusterSize ClusterSize, int hiddenSectors,
            BlockDeviceAPI api) throws IOException {

        if (nbTotalSectors < 65536) {
            throw new IOException("This drive is too small for FAT32 - there must be at least 64K clusters");
        }
        FatFsInfo fsInfo = new FatFsInfo(sectorSize);
        BootSector bs = new BootSector(sectorSize);
        api.flush();
        // long, not int: a device over 4 GiB wraps this in int arithmetic (at
        // Integer.MAX_VALUE sectors it evaluated to -512 and the log reported
        // "Size(Bytes): -512"). Ticket #878.
        long DiskSize = getDiskSize(nbTotalSectors, sectorSize);
        int SectorPerCluster = get_spc(ClusterSize, sectorSize);
        this.FatSize =
                getFATSizeSectors(nbTotalSectors, ReservedSectorCount, SectorPerCluster, NumOfFATs,
                        sectorSize);
        // FAT32: BPB_RootEntCnt is 0, so the root directory occupies no fixed
        // sectors. Kept in the formula so the total stays correct if a fixed
        // root ever returns.
        int rootEntCnt = 0;
        long rootDirSectors = ((long) rootEntCnt * 32 + sectorSize - 1) / sectorSize;
        long dataSectors = (long) nbTotalSectors
                - ((long) ReservedSectorCount + (long) NumOfFATs * FatSize + rootDirSectors);
        long countOfClusters = dataSectors / SectorPerCluster;
        // >2^28 clusters cannot be represented in a FAT32 entry; refuse before
        // any write and leave FSI_FreeCount at its 0xffffffff (unknown) default.
        if (countOfClusters > (1L << 28)) {
            throw new IOException("This drive has more than 2^28 clusters, try to specify a larger cluster size");
        }

        // fill out the boot sector and fs info
        bs.setBS_JmpBoot(this.BS_jmpBoot);
        bs.setBS_OemName("MSWIN4.1");
        bs.setBPB_BytesPerSector(sectorSize);
        bs.setBPB_SecPerCluster(SectorPerCluster); // look FATformat source
        bs.setBPB_RsvSecCount(ReservedSectorCount);
        bs.setBPB_NoFATs(NumOfFATs);
        bs.setBPB_RootEntCnt(rootEntCnt);
        bs.setBPB_TotSec16(0);
        bs.setBPB_MediumDescriptor(mediumDescriptor);
        bs.setBPB_FATSz16(0);
        bs.setBPB_SecPerTrk(SECTOR_PER_TRACK);
        bs.setBPB_NumHeads(NB_HEADS);
        bs.setBPB_HiddSec(hiddenSectors);
        bs.setBPB_TotSec32(nbTotalSectors);

        // For FAT16/32 only
        bs.setBPB_FATSz32(FatSize);
        bs.setBPB_ExtFlags(0);
        bs.setBPB_FSVer(0);
        bs.setBPB_RootClus(2);
        bs.setBPB_FSInfo(1);
        bs.setBPB_BkBootSec(BackupBootSector);

        bs.setBS_DrvNum(0x80);
        bs.setBS_Reserved1(0);
        bs.setBS_BootSig(0x29); // 0x29 if the next three bits are OK.

        int VolumeID = getDriveSerialNumber();
        bs.setBS_VolID(VolumeID);
        bs.setBS_VolLab(VOL_LABEL);
        bs.setBS_FilSysType(FAT_LABEL);
        bs.setBS_Identifier(BS_Identifier);

        // Keeping the FSInfos
        fsInfo.setFsInfo_LeadSig(FSI_LeadSig);
        fsInfo.setFsInfo_Reserved1();
        fsInfo.setFsInfo_StrucSig(FSI_StrucSig);
        FSI_FreeCount = (int) (countOfClusters - 1);
        fsInfo.setFsInfo_FreeCount(FSI_FreeCount);
        fsInfo.setFsInfo_NextFree(FSI_Nxt_Free);
        fsInfo.setReserve2();
        fsInfo.setFsInfo_TrailSig(FSI_TrailSig);

        /**
         * TODO:This portion need modofication for the Disk Size Handlings Not
         * So mandatory now .WIll look into it. Work out the Cluster Count
         */
        /**
         * Once zero_sectors has run, any data on the drive is basically
         * lost.... First zero out ReservedSect + FatSize * NumFats +
         * SectorsPerCluster
         *
         */
        int SystemAreaSize = (ReservedSectorCount + (NumOfFATs * FatSize) + SectorPerCluster);
        log.info("Clearing out " + SystemAreaSize +
                " sectors for Reserved sectors, fats and root cluster...\n");

        /** Disk Freeing */
        try {
            setQuickSectorFree(SystemAreaSize, api);
        } catch (IOException e1) {
            log.info("Error ocured during Disk Free.");
        }
        /** calling the Format method */
        try {
            log.info("Initialising reserved sectors and FATs....");
            format(api, bs, fsInfo, SectorPerCluster, nbTotalSectors);
        } catch (IOException e) {
            log.error("Problems in Disk Formatting.");
        }

        /**
         * The mkjfat informations group
         */
        long DiskSizeGB = DiskSize / (1000L * 1000 * 1000);
        log.info("Size(Bytes): " + DiskSize + "\tSize(GB): " + DiskSizeGB + "\tSectors :" +
                nbTotalSectors);
        log.info("Volume ID is :" + toHexString(VolumeID >> 16) + ":" +
                toHexString(VolumeID & 0xffff));
        log.info(sectorSize + " Bytes Per Sector , Cluster Size  " + ClusterSize + " bytes.");
        log.info(ReservedSectorCount + " Reserved Sectors ," + (FatSize) + " Sectors Per FAT ," +
                NumOfFATs + " FATs.");
        log.info("Total Clusters :" + countOfClusters);
        log.info("Free Clusters: " + FSI_FreeCount);

        // Flushing the DeviceAPI
        api.flush();
    }

    /**
     * Format the given device, according to my settings
     *
     * @param api
     * @throws IOException
     */
    public void format(BlockDeviceAPI api, BootSector bs, FatFsInfo fsInfo, int sectorPerCluster,
            int nbTotalSectors) throws IOException {
        log.info("The Formatting...\n");
        /**
         * Now we should write the boot sector and fsinfo twice, once at 0 and
         * once at the backup boot sect position
         */
        for (int i = 0; i < 2; i++) {
            int SectorStart = (i == 0) ? 0 : (BackupBootSector * 512);
            bs.write(api, (long) SectorStart); // Write the BootSector
            fsInfo.write(api, (long) SectorStart + 512);
        }
        /** Write the first fat sector in the right places */
        for (int i = 0; i < NumOfFATs; i++) {
            // long: (ReservedSectorCount + (i * FatSize)) * 512 wraps in int past
            // 4,194,304 sectors. Ticket #878.
            long SectorStart = ((long) (ReservedSectorCount + (i * FatSize))) * 512;
            this.reservedSector = new byte[12];
            LittleEndian.setInt32(this.reservedSector, 0, ReservedSector_0);
            LittleEndian.setInt32(this.reservedSector, 4, ReservedSector_1);
            LittleEndian.setInt32(this.reservedSector, 8, ReservedSector_2);
            api.write(SectorStart, ByteBuffer.wrap(this.reservedSector));

        }

    }

    /**
     * The Method for Disk Free Operations.
     *
     * @param systemAreaSize
     * @param api
     * @throws IOException
     */
    private void setQuickSectorFree(int systemAreaSize, BlockDeviceAPI api) throws IOException {
        byte[] reserveArray = new byte[512];
        for (int i = 0; i < systemAreaSize; i++) {
            // long offset: i * 512 in int arithmetic wraps past 4,194,304 sectors.
            // Ticket #878.
            api.write(((long) i) * 512, ByteBuffer.wrap(reserveArray));
        }
    }

    /**
     * This is for Quick Disk Free Operation TODO:Need to test with it.
     *
     * @param TotalSectors
     * @param api
     * @throws IOException
     */
    @SuppressWarnings("unused")
    private void setQuickDiskFree(int TotalSectors, BlockDeviceAPI api) throws IOException {
        // TODO:For Total Disk Formatting
    }

    /**
     * @param clusterSize
     * @param BytesPerSector
     * @return
     */
    private static int get_spc(ClusterSize clusterSize, int BytesPerSector) {
        return (clusterSize.getSize() / BytesPerSector);
    }

    /**
     * The method for getting the Serial Number of the Drive.
     *
     * FatUtils takes epoch MILLISECONDS, and date.getTime() returns them, so the
     * raw long is passed through. Passing (int) date.getTime() truncated ~1.76e12
     * to its low 32 bits and fed Calendar a garbage epoch, which made the volume
     * serial an arbitrary value rather than the date. Ticket #878.
     */
    public static int getDriveSerialNumber() {
        return getDriveSerialNumber(new Date().getTime());
    }

    /**
     * The serial number for a given instant, factored out of
     * {@link #getDriveSerialNumber()} so it can be tested without racing the
     * clock: the caller's "now" and the recalculation must not be two different
     * instants, or a second boundary between them fails an otherwise-correct
     * serial.
     *
     * FatUtils takes epoch MILLISECONDS, and the parameter is exactly that, so
     * every field is derived from it. Passing (int) time truncated ~1.76e12 to
     * its low 32 bits and fed Calendar a garbage epoch, which made the volume
     * serial an arbitrary value rather than the date. Ticket #878.
     */
    public static int getDriveSerialNumber(long time) {
        int year = FatUtils.getYear(time);
        int month = FatUtils.getMonth(time);
        int day = FatUtils.getDay(time);
        int hour = FatUtils.getHours(time);
        int minute = FatUtils.getMinutes(time);
        int second = FatUtils.getSeconds(time);
        int milliSecond = (int) FatUtils.getMilliSeconds(time);

        int low = day + (month << 8);
        int tmp = (milliSecond / 10) + (second << 8);
        low += tmp;

        int high = minute + (hour << 8);
        high += year;

        int serialNo = low + (high << 16);
        return serialNo;
    }

    /**
     * Get the FAT size sectors.
     */
    public int getFATSizeSectors(int TotalSectorNumber, int ReservedSecCnt, int SecPerClus,
            int NumFATs, int BytesPerSect) {
        long Numerator, Denominator;
        long FATElementSize = 4;
        long FATsz;

        Numerator = FATElementSize * (TotalSectorNumber - ReservedSecCnt);
        Denominator = (SecPerClus * BytesPerSect) + (FATElementSize * NumFATs);

        FATsz = Numerator / Denominator;
        // round up
        FATsz += 1;

        return ((int) FATsz);
    }

    /**
     * The Total Disk Size in bytes. Computed in long: sector counts above 4 GiB
     * wrap in int arithmetic, which is how this reported "-512" for a
     * Integer.MAX_VALUE-sector device. Ticket #878.
     *
     * @param TotalSectors
     * @param sectorSize
     * @return the size in bytes
     */
    public static long getDiskSize(int TotalSectors, int sectorSize) {
        return ((long) TotalSectors) * sectorSize;
    }

}
