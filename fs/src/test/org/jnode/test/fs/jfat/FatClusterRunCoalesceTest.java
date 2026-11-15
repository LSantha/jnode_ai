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
import java.util.ArrayList;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the device-request counts for sequential JFAT I/O after the
 * contiguous-run coalescing was added to {@code FatChain.read}/
 * {@code FatChain.writeDataClusters}:
 * <ul>
 * <li>an unfragmented 1 MiB read issues
 * {@code ceil(bytes / MAX_SECTOR_COUNT_28 / SECTOR_SIZE / sectorsPerCluster)}
 * device reads (8 with 4 KiB clusters), never one request per cluster;</li>
 * <li>a fragmented file (interleaved allocation) still issues exactly one
 * device read per cluster, with byte-identical content;</li>
 * <li>an unfragmented 1 MiB write issues the same coalesced count;</li>
 * <li>random cluster-aligned reads and a sub-cluster file are unchanged.</li>
 * </ul>
 *
 * <p>Only file-data clusters are counted: requests below the first data
 * cluster (FAT, boot sector, and the root directory cluster) are ignored, so
 * directory bookkeeping cannot inflate the counts.</p>
 */
public class FatClusterRunCoalesceTest {

    /** 68 MiB: comfortably above the formatter's enforced 64K-sector FAT32 floor. */
    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;
    private static final int CLUSTER_SIZE = 4096;
    private static final int SECTORS_PER_CLUSTER = CLUSTER_SIZE / IDEConstants.SECTOR_SIZE;
    private static final int FILE_CLUSTERS = 256;
    private static final int FILE_SIZE = FILE_CLUSTERS * CLUSTER_SIZE;
    private static final int MAX_RUN_CLUSTERS = IDEConstants.MAX_SECTOR_COUNT_28 / SECTORS_PER_CLUSTER;
    private static final int EXPECTED_COALESCED_REQUESTS = FILE_CLUSTERS / MAX_RUN_CLUSTERS;

    private FileSystemService fss;
    private File diskFile;
    private CountingFileDevice fileDevice;
    private long firstDataByte;

    @Before
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
        diskFile = File.createTempFile("jfat-cluster-run-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        closeQuietly(fileDevice);
        fileDevice = null;
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testUnfragmentedSequentialReadCoalesces() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("big.bin").getFile();
        byte[] pattern = patternBytes(FILE_SIZE);
        file.write(0, ByteBuffer.wrap(pattern));
        file.flush();
        fs.flush();

        fileDevice.resetCounts();
        ByteBuffer dst = ByteBuffer.allocate(FILE_SIZE);
        file.read(0, dst);

        Assert.assertEquals("unfragmented 1 MiB read must issue one request per " + MAX_RUN_CLUSTERS
                + " clusters (" + (IDEConstants.MAX_SECTOR_COUNT_28 * IDEConstants.SECTOR_SIZE)
                + " bytes), not one per cluster",
            EXPECTED_COALESCED_REQUESTS, fileDevice.dataReads);
        Assert.assertEquals(FILE_SIZE, fileDevice.dataReadBytes);
        Assert.assertEquals(MAX_RUN_CLUSTERS * (long) CLUSTER_SIZE, fileDevice.maxDataReadLength);
        Assert.assertArrayEquals(pattern, dst.array());
        fs.close();
    }

    @Test
    public void testFragmentedReadUnchangedInCountAndResult() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();

        FSFile a = root.addFile("a.bin").getFile();
        FSFile b = root.addFile("b.bin").getFile();
        byte[] chunkA = patternBytes(CLUSTER_SIZE);
        byte[] chunkB = new byte[CLUSTER_SIZE];
        for (int i = 0; i < chunkB.length; i++) {
            chunkB[i] = (byte) (chunkA[i] ^ 0x5A);
        }
        for (int off = 0; off < FILE_SIZE; off += CLUSTER_SIZE) {
            a.write(off, ByteBuffer.wrap(chunkA));
            b.write(off, ByteBuffer.wrap(chunkB));
        }
        a.flush();
        b.flush();
        fs.flush();

        FatChain aChain = ((org.jnode.fs.jfat.FatFile) a).getChain();
        List<Integer> chain = chainClusters(fs.getFat(), aChain.getStartCluster());
        Assert.assertEquals("a.bin should own exactly " + FILE_CLUSTERS + " clusters",
            FILE_CLUSTERS, chain.size());
        for (int i = 1; i < chain.size(); i++) {
            Assert.assertFalse("a.bin chain must be fragmented (cluster " + chain.get(i - 1) + " -> "
                    + chain.get(i) + ") or this test proves nothing",
                chain.get(i).intValue() == chain.get(i - 1).intValue() + 1);
        }

        fileDevice.resetCounts();
        ByteBuffer dst = ByteBuffer.allocate(FILE_SIZE);
        a.read(0, dst);

        Assert.assertEquals("a fragmented chain has no run to coalesce, so every cluster is one request",
            FILE_CLUSTERS, fileDevice.dataReads);
        Assert.assertEquals(CLUSTER_SIZE, fileDevice.maxDataReadLength);

        byte[] expected = new byte[FILE_SIZE];
        for (int off = 0; off < FILE_SIZE; off += CLUSTER_SIZE) {
            System.arraycopy(chunkA, 0, expected, off, CLUSTER_SIZE);
        }
        Assert.assertArrayEquals(expected, dst.array());

        fs.close();
    }

    @Test
    public void testRandomAlignedReadsUnchanged() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("big.bin").getFile();
        byte[] pattern = patternBytes(FILE_SIZE);
        file.write(0, ByteBuffer.wrap(pattern));
        file.flush();
        fs.flush();

        long[] offsets = {0, 7L * CLUSTER_SIZE, 32L * CLUSTER_SIZE,
            129L * CLUSTER_SIZE, (FILE_CLUSTERS - 1L) * CLUSTER_SIZE};
        for (int i = 0; i < offsets.length; i++) {
            fileDevice.resetCounts();
            ByteBuffer dst = ByteBuffer.allocate(CLUSTER_SIZE);
            file.read(offsets[i], dst);
            Assert.assertEquals("a single aligned cluster read must be exactly one device read at offset "
                    + offsets[i], 1, fileDevice.dataReads);
            byte[] expected = new byte[CLUSTER_SIZE];
            System.arraycopy(pattern, (int) offsets[i], expected, 0, CLUSTER_SIZE);
            Assert.assertArrayEquals(expected, dst.array());
        }

        fs.close();
    }

    @Test
    public void testUnfragmentedSequentialWriteCoalesces() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("big.bin").getFile();
        fs.flush();

        byte[] pattern = patternBytes(FILE_SIZE);
        fileDevice.resetCounts();
        file.write(0, ByteBuffer.wrap(pattern));

        Assert.assertEquals("unfragmented 1 MiB write must issue one request per " + MAX_RUN_CLUSTERS
                + " clusters, not one per cluster",
            EXPECTED_COALESCED_REQUESTS, fileDevice.dataWrites);
        Assert.assertEquals(FILE_SIZE, fileDevice.dataWriteBytes);
        Assert.assertEquals(MAX_RUN_CLUSTERS * (long) CLUSTER_SIZE, fileDevice.maxDataWriteLength);

        fs.close();
    }

    @Test
    public void testSingleClusterFileUnchanged() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("small.bin").getFile();
        fs.flush();

        byte[] data = patternBytes(512);
        fileDevice.resetCounts();
        file.write(0, ByteBuffer.wrap(data));

        Assert.assertEquals(1, fileDevice.dataWrites);

        fileDevice.resetCounts();
        ByteBuffer dst = ByteBuffer.allocate(512);
        file.read(0, dst);

        Assert.assertEquals(1, fileDevice.dataReads);
        Assert.assertArrayEquals(data, dst.array());

        fs.close();
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new CountingFileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_LENGTH);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._4Kb);
        FatFileSystem fs = formatter.format(fileDevice);
        BootSector bs = fs.getFat().getBootSector();
        firstDataByte = ((long) bs.getNrReservedSectors()
            + (long) bs.getNrFats() * bs.getSectorsPerFat()) * IDEConstants.SECTOR_SIZE;
        fileDevice.setFirstDataByte(firstDataByte + CLUSTER_SIZE);
        return fs;
    }

    private List<Integer> chainClusters(Fat fat, int start) throws IOException {
        List<Integer> list = new ArrayList<Integer>();
        int cursor = start;
        while (cursor >= fat.firstCluster() && !fat.isEofChain(fat.get(cursor))) {
            list.add(Integer.valueOf(cursor));
            cursor = fat.get(cursor);
        }
        if (cursor >= fat.firstCluster()) {
            list.add(Integer.valueOf(cursor));
        }
        return list;
    }

    private static byte[] patternBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (0x5A + i * 31 + (i >>> 7));
        }
        return data;
    }

    private static void closeQuietly(FileDevice device) {
        try {
            if (device != null) {
                device.close();
            }
        } catch (Exception e) {
            // already closed
        }
    }

    /**
     * Counts device requests that touch file-data clusters (offset at or above
     * the first data cluster, i.e. past the root directory cluster). FAT, boot
     * sector and directory traffic is below that bound and never counted, so
     * the counts pin exactly the data path under test.
     */
    private static class CountingFileDevice extends FileDevice {
        private long firstDataByte = Long.MAX_VALUE;
        private int dataReads;
        private int dataWrites;
        private long dataReadBytes;
        private long dataWriteBytes;
        private long maxDataReadLength;
        private long maxDataWriteLength;

        private CountingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        private void setFirstDataByte(long value) {
            this.firstDataByte = value;
        }

        private void resetCounts() {
            dataReads = 0;
            dataWrites = 0;
            dataReadBytes = 0;
            dataWriteBytes = 0;
            maxDataReadLength = 0;
            maxDataWriteLength = 0;
        }

        @Override
        public void read(long devOffset, ByteBuffer destBuf) throws IOException {
            if (devOffset >= firstDataByte) {
                dataReads++;
                dataReadBytes += destBuf.remaining();
                if (destBuf.remaining() > maxDataReadLength) {
                    maxDataReadLength = destBuf.remaining();
                }
            }
            super.read(devOffset, destBuf);
        }

        @Override
        public void write(long devOffset, ByteBuffer srcBuf) throws IOException {
            if (devOffset >= firstDataByte) {
                dataWrites++;
                dataWriteBytes += srcBuf.remaining();
                if (srcBuf.remaining() > maxDataWriteLength) {
                    maxDataWriteLength = srcBuf.remaining();
                }
            }
            super.write(devOffset, srcBuf);
        }
    }
}
