/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
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
import java.util.ArrayList;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatFormatter;
import org.jnode.fs.jfat.FatUtils;
import org.jnode.fs.jfat.FatFileSystemType;

/**
 * End-to-end proof for JFAT's on-disk writer: {@link FatFileSystemFormatter} had no test
 * at all, and every other JFAT test reads a pre-made image, so a formatter bug would
 * corrupt every disk it touched with nothing noticing. This test formats blank images,
 * writes through the normal filesystem API, unmounts, remounts and verifies the bytes.
 * <p>
 * The formatter is FAT32 only: {@code FatFileSystemFormatter.format} delegates to
 * {@code FatFormatter.HDDFormatter}, which always writes a FAT32 BPB (BPB_FATSz32,
 * BPB_RootClus=2, an FSInfo sector) - there is no FAT12/FAT16 branch to cover.
 *
 * @see <a href="https://github.com/LSantha/jnode_ai/issues/868">ticket #868</a>
 */
public class FatFormatterRoundTripTest {

    /** 68 MiB: comfortably above the formatter's enforced 64K-sector FAT32 floor. */
    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;

    /** 1 KiB clusters, the size the existing write tests use. */
    private static final ClusterSize CLUSTER_SIZE = ClusterSize._1Kb;

    /** A deterministic, position-dependent pattern so a corruption shows up. */
    private static final byte patternByte(int i) {
        return (byte) (i * 31 + 7);
    }

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
        diskFile = File.createTempFile("jfat-formatter-roundtrip", ".img");
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

    /**
     * Item 1: a freshly formatted volume mounts, is FAT32, and its boot-sector fields
     * match what the formatter claims to write.
     */
    @org.junit.Test
    public void testFormatProducesMountableFatStructures() throws Exception {
        FatFileSystem fs = formatAndOpen();

        BootSector bs = fs.getBootSector();
        org.junit.Assert.assertTrue("formatter writes FAT32 only", bs.isFat32());
        org.junit.Assert.assertEquals(512, bs.getBytesPerSector());
        // ClusterSize._1Kb is 2 sectors of 512 bytes.
        org.junit.Assert.assertEquals(2, bs.getSectorsPerCluster());
        org.junit.Assert.assertEquals(2, bs.getNrFats());
        org.junit.Assert.assertEquals(2, bs.getRootDirectoryStartCluster());
        org.junit.Assert.assertEquals(0, bs.getNrRootDirEntries());
        org.junit.Assert.assertTrue("countOfClusters", bs.getCountOfClusters() > 0);
        org.junit.Assert.assertTrue("firstDataSector", bs.getFirstDataSector() > 0);
        // FatFormatter.VOL_LABEL: the formatter never takes a label from the caller.
        org.junit.Assert.assertEquals("NO NAME    ", bs.getVolumeLabel());

        // The root directory is empty after format (no volume-label entry, no dot entries).
        FSDirectory root = fs.getRootEntry().getDirectory();
        org.junit.Assert.assertEquals(0, countEntries(root));

        fs.close();
    }

    /**
     * Item 1, FAT side: the reserved entries the FAT spec fixes for FAT32 - entry 0 is
     * the media descriptor with the reserved high bits, entry 1 is end-of-chain, and the
     * root directory cluster (2) terminates its own single-cluster chain.
     */
    @org.junit.Test
    public void testFatReservedEntriesAfterFormat() throws Exception {
        FatFileSystem fs = formatAndOpen();
        Fat fat = fs.getFat();

        org.junit.Assert.assertEquals(0x0FFFFFF8L, fat.get(0) & 0xFFFFFFFFL);
        org.junit.Assert.assertEquals(0x0FFFFFFFL, fat.get(1) & 0xFFFFFFFFL);
        org.junit.Assert.assertTrue("FAT[1] is end-of-chain", fat.isEofChain(fat.get(1)));
        // The formatter sets BPB_RootClus = 2 and allocates that one cluster.
        org.junit.Assert.assertTrue("root cluster terminates its chain",
            fat.isEofChain(fat.get((int) fs.getBootSector().getRootDirectoryStartCluster())));

        // Every cluster but the root's is free on a blank volume.
        long expectedFree = bs(fs).getCountOfClusters() - 1;
        org.junit.Assert.assertEquals(expectedFree, fat.freeEntries());
        // size() counts FAT ENTRIES, the data clusters plus the two reserved slots.
        org.junit.Assert.assertEquals(expectedFree + 3, fat.size());

        fs.close();
    }

    /**
     * Items 2 and 3, the core of the ticket: format, build a tree (including a long file
     * name so LFN entries are written), write known multi-cluster content, unmount,
     * remount and verify CONTENT - not just names.
     */
    @org.junit.Test
    public void testRoundTripPreservesTreeAndContent() throws Exception {
        FatFileSystem fs = formatAndOpen();

        byte[] big = new byte[120 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = patternByte(i);
        }
        byte[] small = "long name content".getBytes("US-ASCII");

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSDirectory docs = root.addDirectory("docs").getDirectory();
        FSFile bigFile = docs.addFile("short.bin").getFile();
        bigFile.write(0, ByteBuffer.wrap(big));
        bigFile.flush();
        FSFile longFile = root.addFile("A-Long-File-Name-For-LFN-Entries.txt").getFile();
        longFile.write(0, ByteBuffer.wrap(small));
        longFile.flush();
        fs.flush();
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        FatFileSystem remounted = openReadOnly();
        FSDirectory root2 = remounted.getRootEntry().getDirectory();

        // The long name resolves, which only works if the LFN entries survived on disk.
        FSEntry longBack = root2.getEntry("A-Long-File-Name-For-LFN-Entries.txt");
        org.junit.Assert.assertNotNull("long-named file survived the remount", longBack);
        org.junit.Assert.assertEquals(small.length, longBack.getFile().getLength());
        org.junit.Assert.assertArrayEquals("long-named file content",
            small, readAll(longBack.getFile()));

        // The subdirectory and its multi-cluster file survived byte-identical.
        FSEntry docsBack = root2.getEntry("docs");
        org.junit.Assert.assertNotNull("subdirectory survived the remount", docsBack);
        FSDirectory docs2 = docsBack.getDirectory();
        FSEntry bigBack = docs2.getEntry("short.bin");
        org.junit.Assert.assertNotNull("multi-cluster file survived the remount", bigBack);
        org.junit.Assert.assertEquals(big.length, bigBack.getFile().getLength());
        byte[] readBack = readAll(bigBack.getFile());
        org.junit.Assert.assertEquals(big.length, readBack.length);
        for (int i = 0; i < big.length; i++) {
            org.junit.Assert.assertEquals("content byte " + i, big[i], readBack[i]);
        }

        // Nothing extra appeared: exactly the two names plus the directory.
        org.junit.Assert.assertEquals(2, countEntries(root2));
        org.junit.Assert.assertEquals(1, countEntries(docs2));

        remounted.close();
    }

    /**
     * Item 3: after a format, the allocator hands out no cluster twice - walk the written
     * file's chain and check every cluster appears once, and that the FAT's free/used
     * accounting agrees with the chain that was built.
     */
    @org.junit.Test
    public void testMultiClusterAllocationHandsOutNoClusterTwice() throws Exception {
        FatFileSystem fs = formatAndOpen();
        Fat fat = fs.getFat();
        long freeAtMount = fat.freeEntries();

        int clusters = 40;
        byte[] data = new byte[clusters * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = patternByte(i);
        }
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("chain.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        FatEntry entry = (FatEntry) root.getEntry("chain.bin");
        int start = entry.getChain().getStartCluster();
        List<Long> seen = new ArrayList<Long>();
        int cursor = start;
        while (cursor >= 2 && !fat.isEofChain(fat.get(cursor))) {
            org.junit.Assert.assertFalse("cluster " + cursor + " handed out twice",
                seen.contains(Long.valueOf(cursor)));
            seen.add(Long.valueOf(cursor));
            cursor = fat.get(cursor);
        }
        // The terminating cluster is part of the chain too.
        seen.add(Long.valueOf(cursor));

        org.junit.Assert.assertEquals(clusters, seen.size());
        // The root cluster was the only allocated one at format time.
        org.junit.Assert.assertEquals(freeAtMount - clusters, fat.freeEntries());

        fs.close();
    }

    /**
     * Item 4. The 64K-sector FAT32 floor is enforced (see #874):
     * {@code FatFileSystemFormatter.format} throws {@code FileSystemException} before
     * {@code HDDFormatter} for a sub-floor device, and {@code FatFormatter} throws
     * {@code IOException} before any write for sub-floor sector counts. This test
     * asserts the enforced contract: a 4 MiB device is refused and the image is
     * left untouched (no half-written boot sector).
     */
    @org.junit.Test
    public void testSubFloorDeviceIsRejected() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        // 4 MiB = 8192 sectors, an eighth of the documented floor.
        fileDevice.setLength(4L * 1024 * 1024);
        try {
            new FatFileSystemFormatter(CLUSTER_SIZE).format(fileDevice);
            org.junit.Assert.fail("expected FileSystemException for sub-floor 4 MiB device");
        } catch (FileSystemException e) {
            org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("too small"));
        }
        closeQuietly(fileDevice);
        fileDevice = null;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(diskFile, "r");
        try {
            org.junit.Assert.assertEquals("sub-floor rejection must not write to the device", 0, raf.read());
        } finally {
            raf.close();
        }
    }

    /**
     * Ticket #878, finding A: the formatter's disk-size arithmetic wrapped in int.
     * {@code FatFormatter.getDiskSize} multiplies the sector count by the sector
     * size, which overflows int above 4 GiB - at Integer.MAX_VALUE sectors it
     * evaluated to -512 and the log reported "Size(Bytes): -512". It is now long.
     */
    @org.junit.Test
    public void testDiskSizeDoesNotWrapForHugeSectorCounts() throws Exception {
        org.junit.Assert.assertEquals(1099511627264L,
            FatFormatter.getDiskSize(Integer.MAX_VALUE, 512));
        org.junit.Assert.assertEquals(0L, FatFormatter.getDiskSize(0, 512));
        // A plain 68 MiB volume is unaffected by the widening.
        org.junit.Assert.assertEquals(68L * 1024 * 1024,
            FatFormatter.getDiskSize((int) ((68L * 1024 * 1024) / 512), 512));
    }

    /**
     * Ticket #878, finding B: the volume serial was built from a truncated epoch.
     * {@code FatFormatter.getDriveSerialNumber} passed {@code (int) date.getTime()}
     * for month/day/hour/minute/second while FatUtils expects epoch MILLISECONDS,
     * so Calendar received a garbage epoch and the serial was arbitrary. The test
     * recomputes the published formula from the same long and requires a match -
     * reintroducing the cast breaks it, whichever the local timezone is.
     */
    @org.junit.Test
    public void testDriveSerialNumberUsesTheUntruncatedEpoch() throws Exception {
        // A fixed instant: the caller's time and the recalculation must be the
        // same instant, or a second boundary between two readings fails an
        // otherwise-correct serial.
        long time = 1762000000000L;
        int year = FatUtils.getYear(time);
        int month = FatUtils.getMonth(time);
        int day = FatUtils.getDay(time);
        int hour = FatUtils.getHours(time);
        int minute = FatUtils.getMinutes(time);
        int second = FatUtils.getSeconds(time);
        int milliSecond = (int) FatUtils.getMilliSeconds(time);

        int low = day + (month << 8);
        low += (milliSecond / 10) + (second << 8);
        int high = minute + (hour << 8);
        high += year;

        org.junit.Assert.assertEquals(low + (high << 16),
            FatFormatter.getDriveSerialNumber(time));
    }

    /**
     * Ticket #878, finding B's other half: FatUtils.getMilliSeconds ignored its
     * argument and returned the CURRENT epoch millis, so the serial's low word
     * was still arbitrary even after the callers stopped truncating. The oracle
     * is an independently built Calendar set to the same instant, not a
     * recalculation of the same broken call.
     */
    @org.junit.Test
    public void testGetMilliSecondsHonoursItsArgument() throws Exception {
        long time = 1762000000123L;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(time);
        org.junit.Assert.assertEquals(cal.get(java.util.Calendar.MILLISECOND),
            FatUtils.getMilliSeconds(time));
        org.junit.Assert.assertEquals(0L, FatUtils.getMilliSeconds(0L));
    }

    // ------------------------------------------------------------------ helpers

    private BootSector bs(FatFileSystem fs) {
        return fs.getBootSector();
    }

    private FatFileSystem formatAndOpen() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_LENGTH);
        return new FatFileSystemFormatter(CLUSTER_SIZE).format(fileDevice);
    }

    private FatFileSystem openReadOnly() throws Exception {
        closeQuietly(fileDevice);
        fileDevice = new FileDevice(diskFile, "r");
        FileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return (FatFileSystem) type.create(fileDevice, true);
    }

    /** Directory iteration yields "." and ".."; they are not user entries. */
    private int countEntries(FSDirectory dir) throws Exception {
        int count = 0;
        java.util.Iterator it = dir.iterator();
        while (it.hasNext()) {
            FSEntry e = (FSEntry) it.next();
            if (!".".equals(e.getName()) && !"..".equals(e.getName())) {
                count++;
            }
        }
        return count;
    }

    private byte[] readAll(FSFile file) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate((int) file.getLength());
        file.read(0, buf);
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        return out;
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
