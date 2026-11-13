/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;

/**
 * The runtime half of the FSInfo policy decided in ticket #898 (policy (b):
 * deliberately stale). The format-time half is pinned by
 * {@link FatFormatterRoundTripTest#testFsiFieldsAreWrittenAsUnknown()} and
 * {@code FatFormatterRawStructureTest}; this test pins what happens to those
 * bytes once the volume is used.
 * <p>
 * {@code FatFormatter} writes {@code FSI_Free_Count} and
 * {@code FSI_Nxt_Free} as {@code 0xFFFFFFFF} ("unknown, must be computed")
 * and nothing in {@code org.jnode.fs.jfat} ever reads or writes them after
 * format time. This test asserts, reading raw sectors 1 and 7 (primary and
 * backup FSInfo) with {@link java.io.RandomAccessFile} rather than through
 * accessors:
 * <ul>
 * <li>a freshly formatted volume holds the sentinel,</li>
 * <li>the sentinel is unchanged by create/delete/flush/close, and</li>
 * <li>a raw-patched implausible {@code FSI_Free_Count} on an existing volume
 * is ignored by JFAT's free-space reporting.</li>
 * </ul>
 * Free-space reporting itself is checked against the live allocator
 * ({@code Fat.getFreeClusterCount() * clusterSize}); the full pin that
 * counter == {@code freeEntries()} scan lives in {@link FatFreeClusterCountTest}
 * and is deliberately not duplicated here. It is seeded by a full FAT scan and
 * maintained incrementally, never from FSInfo.
 *
 * @see <a href="https://github.com/LSantha/jnode_ai/issues/898">ticket #898</a>
 */
public class FatFsInfoRuntimeTest {

    /** 68 MiB: comfortably above the formatter's enforced 64K-sector FAT32 floor. */
    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;

    /** 1 KiB clusters, the size the existing write tests use. */
    private static final ClusterSize CLUSTER_SIZE = ClusterSize._1Kb;

    private FileSystemService fss;
    private File diskFile;
    private FileDevice fileDevice;
    private File workImage;

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
        diskFile = File.createTempFile("jfat-fsinfo-runtime", ".img");
        diskFile.deleteOnExit();
    }

    @org.junit.After
    public void tearDown() throws Exception {
        closeQuietly(fileDevice);
        fileDevice = null;
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
        if (workImage != null && workImage.exists()) {
            workImage.delete();
        }
    }

    /**
     * A freshly formatted volume holds the 0xFFFFFFFF sentinel in both the
     * primary and the backup FSInfo sector, before anything mounts it.
     */
    @org.junit.Test
    public void testFreshFormatLeavesFsInfoUnknown() throws Exception {
        formatRaw();

        assertFsInfoUnknown(diskFile, "fresh format");
    }

    /**
     * Create and delete files, flush and close: the sentinel must survive
     * untouched in both FSInfo sectors, and free-space reporting must track
     * the live allocator throughout, not the stale on-disk bytes. Deleting
     * the file must return free space to the at-mount baseline both before
     * and after a close/reopen cycle.
     */
    @org.junit.Test
    public void testFsInfoRemainsUnknownAfterCreateDeleteFlushClose() throws Exception {
        FatFileSystem fs = formatAndOpen();
        Fat fat = fs.getFat();
        long freeAtMount = fs.getFreeSpace();
        assertFreeSpaceMatchesCounter(fs, "at mount");
        org.junit.Assert.assertEquals("sentinel volume starts with full FAT scan available",
            freeAtMount, fat.freeEntries() * (long) fs.getClusterSize());

        int clusterSize = fs.getClusterSize();
        byte[] data = new byte[clusterSize * 3];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("sentinel.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();
        org.junit.Assert.assertEquals("allocating 3 clusters drops reported free space",
            freeAtMount - 3L * clusterSize, fs.getFreeSpace());
        assertFreeSpaceMatchesCounter(fs, "after writing 3 clusters");

        root.remove("sentinel.bin");
        fs.flush();
        org.junit.Assert.assertEquals("deleting the file returns reported free space",
            freeAtMount, fs.getFreeSpace());
        assertFreeSpaceMatchesCounter(fs, "after delete");
        fs.flush();
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        assertFsInfoUnknown(diskFile, "after create/delete/flush/close");

        FatFileSystem remounted = openReadOnly();
        org.junit.Assert.assertEquals("close/reopen keeps the delete visible to the allocator",
            freeAtMount, remounted.getFreeSpace());
        remounted.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        assertFsInfoUnknown(diskFile, "after close/reopen");
    }

    /**
     * A volume whose raw FSI_Free_Count has been patched to something
     * implausible (far above the cluster count of a 68 MiB volume) mounts and
     * reports free space from the FAT scan anyway: the stored value is not
     * trusted. The patched bytes are also left in place, not repaired.
     */
    @org.junit.Test
    public void testImplausibleFsInfoIsIgnoredOnMount() throws Exception {
        formatRaw();
        int bytesPerSec = (int) readLe16(diskFile, 11);
        long absurd = 0x7fffffffL;
        for (long sector : new long[] {1L, 7L}) {
            writeLe32(diskFile, sector * bytesPerSec + 488, absurd);
            writeLe32(diskFile, sector * bytesPerSec + 492, absurd);
        }

        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(fileDevice, false);
        Fat fat = fs.getFat();
        long freeAtMount = fs.getFreeSpace();
        org.junit.Assert.assertEquals("implausible FSI_Free_Count must not leak into reporting",
            (long) fat.getFreeClusterCount() * fs.getClusterSize(), freeAtMount);
        org.junit.Assert.assertTrue("reported free space is bounded by the FAT, not by the patch",
            freeAtMount < absurd * fs.getClusterSize());

        FSDirectory root = fs.getRootEntry().getDirectory();
        int clusterSize = fs.getClusterSize();
        FSFile file = root.addFile("absurd.bin").getFile();
        byte[] data = new byte[clusterSize * 2];
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();
        org.junit.Assert.assertEquals(freeAtMount - 2L * clusterSize, fs.getFreeSpace());
        root.remove("absurd.bin");
        fs.flush();
        org.junit.Assert.assertEquals(freeAtMount, fs.getFreeSpace());
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        for (long sector : new long[] {1L, 7L}) {
            org.junit.Assert.assertEquals("patched FSI_Free_Count survives mount/use",
                absurd, readLe32(diskFile, sector * bytesPerSec + 488));
            org.junit.Assert.assertEquals("patched FSI_Nxt_Free survives mount/use",
                absurd, readLe32(diskFile, sector * bytesPerSec + 492));
        }
    }

    /**
     * The externally created {@code test.fat32.gz} fixture carries a plausible
     * FSInfo from whatever tool wrote it (sector 1: FSI_Free_Count=0x00018cce,
     * FSI_Nxt_Free=8; the backup sector carries the unknown sentinel). JFAT
     * mounts it, reports free space from the FAT scan, and leaves the
     * foreign tool's bytes untouched.
     */
    @org.junit.Test
    public void testForeignToolPlausibleFsInfoIsIgnored() throws Exception {
        workImage = copyFixture("test/fs/jfat/test.fat32", "jfat-fsinfo-foreign");
        int bytesPerSec = (int) readLe16(workImage, 11);
        org.junit.Assert.assertEquals("foreign sector-1 FSI_Free_Count",
            0x00018cceL, readLe32(workImage, 1L * bytesPerSec + 488));
        org.junit.Assert.assertEquals("foreign sector-1 FSI_Nxt_Free",
            8L, readLe32(workImage, 1L * bytesPerSec + 492));

        fileDevice = new FileDevice(workImage, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(fileDevice, false);
        Fat fat = fs.getFat();
        org.junit.Assert.assertTrue("fixture reports a non-trivial free-cluster count",
            fat.getFreeClusterCount() > 0);
        assertFreeSpaceMatchesCounter(fs, "foreign volume at mount");

        FSDirectory root = fs.getRootEntry().getDirectory();
        int clusterSize = fs.getClusterSize();
        long freeAtMount = fs.getFreeSpace();
        FSFile file = root.addFile("foreign-check.bin").getFile();
        byte[] data = new byte[clusterSize];
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();
        org.junit.Assert.assertEquals(freeAtMount - clusterSize, fs.getFreeSpace());
        root.remove("foreign-check.bin");
        fs.flush();
        org.junit.Assert.assertEquals(freeAtMount, fs.getFreeSpace());
        fs.flush();
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        org.junit.Assert.assertEquals("foreign sector-1 FSI_Free_Count left untouched",
            0x00018cceL, readLe32(workImage, 1L * bytesPerSec + 488));
        org.junit.Assert.assertEquals("foreign sector-1 FSI_Nxt_Free left untouched",
            8L, readLe32(workImage, 1L * bytesPerSec + 492));
    }

    // ------------------------------------------------------------------ helpers

    private void assertFsInfoUnknown(File image, String stage) throws Exception {
        int bytesPerSec = (int) readLe16(image, 11);
        for (long sector : new long[] {1L, 7L}) {
            org.junit.Assert.assertEquals("FSI_Free_Count (" + stage + ", sector " + sector + ")",
                0xffffffffL, readLe32(image, sector * bytesPerSec + 488));
            org.junit.Assert.assertEquals("FSI_Nxt_Free (" + stage + ", sector " + sector + ")",
                0xffffffffL, readLe32(image, sector * bytesPerSec + 492));
        }
    }

    private void assertFreeSpaceMatchesCounter(FatFileSystem fs, String stage) {
        Fat fat = fs.getFat();
        org.junit.Assert.assertEquals(stage + ": getFreeSpace must equal live counter * clusterSize",
            (long) fat.getFreeClusterCount() * fs.getClusterSize(), fs.getFreeSpace());
        org.junit.Assert.assertEquals(stage + ": getUsableSpace must equal getFreeSpace",
            fs.getFreeSpace(), fs.getUsableSpace());
    }

    /** Formats without mounting, for the raw-byte checks. */
    private void formatRaw() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_LENGTH);
        new FatFileSystemFormatter(CLUSTER_SIZE).format(fileDevice);
        closeQuietly(fileDevice);
        fileDevice = null;
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

    private static long readLe16(File image, long offset) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(image, "r");
        try {
            raf.seek(offset);
            return (raf.read() & 0xff) | ((raf.read() & 0xff) << 8);
        } finally {
            raf.close();
        }
    }

    private static long readLe32(File image, long offset) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(image, "r");
        try {
            raf.seek(offset);
            return (raf.read() & 0xffL) | ((raf.read() & 0xffL) << 8) | ((raf.read() & 0xffL) << 16)
                | ((raf.read() & 0xffL) << 24);
        } finally {
            raf.close();
        }
    }

    private static void writeLe32(File image, long offset, long value) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(image, "rw");
        try {
            raf.seek(offset);
            raf.write((int) (value & 0xff));
            raf.write((int) ((value >> 8) & 0xff));
            raf.write((int) ((value >> 16) & 0xff));
            raf.write((int) ((value >> 24) & 0xff));
        } finally {
            raf.close();
        }
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
