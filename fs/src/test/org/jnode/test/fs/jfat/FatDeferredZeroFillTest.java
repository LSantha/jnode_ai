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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins the deferred zero-fill contract for gap-extending writes: the gap
 * costs cluster allocation, not per-cluster device writes, and the gap bytes
 * read as zeros before and after the flush that realizes them on the device.
 */
public class FatDeferredZeroFillTest {

    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;
    private static final long GAP_OFFSET = 16L * 1024 * 1024;

    private File diskFile;
    private FileDevice fileDevice;
    private FailingFileDevice device;
    private FileSystemService fss;

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
        diskFile = File.createTempFile("jfat-deferred-zero-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            try {
                fileDevice.close();
            } catch (Exception e) {
            }
            fileDevice = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
            }
            device = null;
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testGapExtendWriteDeviceWritesProportionalToData() throws Exception {
        prefill();
        CountingFSBlockDeviceAPI countingApi = openCountingRW();
        FatFileSystem fs = create();
        FSFile file = fs.getRootEntry().getDirectory().addFile("sparse.bin").getFile();

        byte[] head = pattern(4096);
        file.write(0, ByteBuffer.wrap(head));
        file.flush();
        countingApi.resetWriteStats();

        byte[] payload = pattern(4096);
        file.write(GAP_OFFSET, ByteBuffer.wrap(payload));

        assertTrue("expected device writes proportional to the written data, got "
                + countingApi.writeCount() + " (" + countingApi.bytesWritten() + " bytes)",
            countingApi.writeCount() <= 8);
        assertTrue("expected device bytes proportional to the written data, got "
                + countingApi.bytesWritten(),
            countingApi.bytesWritten() <= 64L * 1024);

        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("sparse.bin").getFile();
        assertEquals(GAP_OFFSET + payload.length, readFile.getLength());
        byte[] data = readAll(readFile);
        assertTrue(Arrays.equals(head, Arrays.copyOfRange(data, 0, head.length)));
        assertTrue(Arrays.equals(payload, Arrays.copyOfRange(data, (int) GAP_OFFSET, data.length)));
        for (int i = head.length; i < data.length - payload.length; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        fs.close();
    }

    @Test
    public void testGapFlushRealizesInBulkRuns() throws Exception {
        prefill();
        CountingFSBlockDeviceAPI countingApi = openCountingRW();
        FatFileSystem fs = create();
        FSFile file = fs.getRootEntry().getDirectory().addFile("bulk.bin").getFile();

        byte[] head = pattern(4096);
        file.write(0, ByteBuffer.wrap(head));
        file.flush();
        fs.flush();
        countingApi.resetWriteStats();

        byte[] payload = pattern(4096);
        file.write(GAP_OFFSET, ByteBuffer.wrap(payload));
        countingApi.resetWriteStats();

        file.flush();

        /*
         * The hole is [4096, GAP_OFFSET) = 4095 clusters. FAT flushes are 512-byte
         * cache elements and the directory entry write is 32 bytes, so every write
         * of 4096 bytes or more is a zero-fill run. With 256 sectors per request
         * and 8 sectors per 4 KiB cluster a run covers at most 32 clusters, so
         * realizing the hole costs at most ceil(4095 / 32) = 128 requests (127
         * full runs plus the 31-cluster tail); a per-cluster fill would be 4095.
         * Two extra requests are tolerated in case the free-cluster scan splits
         * the run.
         */
        int clusterSize = fs.getClusterSize();
        int maxRunClusters = 256 / (clusterSize / 512);
        long holeClusters = (GAP_OFFSET - clusterSize) / clusterSize;
        long holeBytes = GAP_OFFSET - clusterSize;
        long expectedRuns = (holeClusters + maxRunClusters - 1) / maxRunClusters;
        assertTrue("expected at most " + (expectedRuns + 2) + " bulk zero-fill writes, got "
                + countingApi.largeWriteCount(),
            countingApi.largeWriteCount() <= expectedRuns + 2);
        assertTrue("expected at least the " + holeBytes + " hole bytes on the device, got "
                + countingApi.largeBytesWritten(),
            countingApi.largeBytesWritten() >= holeBytes);
        fs.close();
    }

    @Test
    public void testSetLengthGrowDoesNotWriteGapEagerly() throws Exception {
        prefill();
        CountingFSBlockDeviceAPI countingApi = openCountingRW();
        FatFileSystem fs = create();
        FSFile file = fs.getRootEntry().getDirectory().addFile("grow.bin").getFile();

        countingApi.resetWriteStats();
        file.setLength(GAP_OFFSET);

        assertEquals("setLength must not issue device writes for the gap",
            0, countingApi.writeCount());
        assertEquals(0, countingApi.bytesWritten());
        assertEquals(GAP_OFFSET, file.getLength());
        byte[] gap = new byte[1024 * 1024];
        readFully(file, 4L * 1024 * 1024, gap);
        assertAllZero(gap);

        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("grow.bin");
        assertEquals(GAP_OFFSET, entry.getFile().getLength());
        gap = new byte[1024 * 1024];
        readFully(entry.getFile(), 4L * 1024 * 1024, gap);
        assertAllZero(gap);
        fs.close();
    }

    @Test
    public void testGapReadReturnsZerosBeforeFlush() throws Exception {
        prefill();
        CountingFSBlockDeviceAPI countingApi = openCountingRW();
        FatFileSystem fs = create();
        FSFile file = fs.getRootEntry().getDirectory().addFile("zero.bin").getFile();

        byte[] payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        file.write(GAP_OFFSET, ByteBuffer.wrap(payload));

        byte[] gap = new byte[8192];
        readFully(file, 8L * 1024 * 1024, gap);
        assertAllZero(gap);

        byte[] atBoundary = new byte[4096];
        readFully(file, GAP_OFFSET - 4096, atBoundary);
        assertAllZero(atBoundary);

        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("zero.bin").getFile();
        gap = new byte[8192];
        readFully(readFile, 8L * 1024 * 1024, gap);
        assertAllZero(gap);
        atBoundary = new byte[4096];
        readFully(readFile, GAP_OFFSET - 4096, atBoundary);
        assertAllZero(atBoundary);
        fs.close();
    }

    @Test
    public void testWithinClusterBeyondEofGapReadsZeros() throws Exception {
        prefill();
        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("within.bin").getFile();
        file.write(0, ByteBuffer.wrap(pattern(100)));
        file.write(500, ByteBuffer.wrap(new byte[] {42, 42, 42, 42}));
        assertEquals(504, file.getLength());
        byte[] data = readAll(file);
        for (int i = 100; i < 500; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        assertEquals((byte) 42, data[500]);
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("within.bin").getFile();
        data = readAll(readFile);
        for (int i = 100; i < 500; i++) {
            assertEquals("persisted gap byte " + i, (byte) 0, data[i]);
        }
        fs.close();
    }

    @Test
    public void testCrashDuringGapRealizationKeepsGapUnexposed() throws Exception {
        prefill();
        device = new FailingFileDevice(diskFile, "rw");
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._4Kb).format(device);
        FSFile file = fs.getRootEntry().getDirectory().addFile("crash.bin").getFile();
        byte[] head = pattern(4096);
        file.write(0, ByteBuffer.wrap(head));
        file.flush();
        fs.flush();
        fs.close();
        fs = openReadWriteForCrashTest();
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("crash.bin");
        file = entry.getFile();

        byte[] payload = new byte[] {42, 42, 42, 42};
        file.write(GAP_OFFSET, ByteBuffer.wrap(payload));

        device.armFailAfterWrites(3);
        try {
            file.flush();
            fail("expected IOException from the failing device mid-flush");
        } catch (IOException expected) {
        }
        device.disarm();

        byte[] gap = new byte[4096];
        readFully(file, 8L * 1024 * 1024, gap);
        assertAllZero(gap);

        device.close();
        device = null;
        fs = openReadOnly();
        entry = fs.getRootEntry().getDirectory().getEntry("crash.bin");
        assertEquals("unclean shutdown must expose only the last persisted length",
            4096, entry.getFile().getLength());
        fs.close();
    }

    @Test
    public void testGapFlushesRetryAfterFailure() throws Exception {
        prefill();
        device = new FailingFileDevice(diskFile, "rw");
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._4Kb).format(device);
        FSFile file = fs.getRootEntry().getDirectory().addFile("retry.bin").getFile();
        byte[] head = pattern(4096);
        file.write(0, ByteBuffer.wrap(head));

        byte[] payload = new byte[] {1, 2, 3, 4};
        file.write(GAP_OFFSET, ByteBuffer.wrap(payload));

        device.armFailAfterWrites(3);
        try {
            file.flush();
            fail("expected IOException from the failing device mid-flush");
        } catch (IOException expected) {
        }
        device.disarm();

        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("retry.bin").getFile();
        assertEquals(GAP_OFFSET + payload.length, readFile.getLength());
        byte[] data = readAll(readFile);
        for (int i = head.length; i < data.length - payload.length; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        fs.close();
    }

    private FatFileSystem openReadWriteForCrashTest() throws Exception {
        device.close();
        device = new FailingFileDevice(diskFile, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return (FatFileSystem) type.create(device, false);
    }

    private void readFully(FSFile file, long offset, byte[] dst) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(dst);
        file.read(offset, buf);
        assertEquals("read at " + offset + " returned short buffer", dst.length, buf.position());
    }

    private void assertAllZero(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            assertEquals("byte " + i, (byte) 0, data[i]);
        }
    }

    private byte[] readAll(FSFile file) throws Exception {
        int length = (int) file.getLength();
        ByteBuffer buf = ByteBuffer.allocate(length);
        file.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private void prefill() throws Exception {
        RandomAccessFile raf = new RandomAccessFile(diskFile, "rw");
        try {
            raf.setLength(IMAGE_LENGTH);
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 0xAB);
            raf.seek(0);
            long remaining = IMAGE_LENGTH;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                raf.write(chunk, 0, n);
                remaining -= n;
            }
        } finally {
            raf.close();
        }
    }

    private CountingFSBlockDeviceAPI openCountingRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        CountingFSBlockDeviceAPI countingApi = new CountingFSBlockDeviceAPI(fileDevice);
        fileDevice.unregisterAPI(FSBlockDeviceAPI.class);
        fileDevice.unregisterAPI(org.jnode.driver.block.BlockDeviceAPI.class);
        fileDevice.registerAPI(FSBlockDeviceAPI.class, countingApi);
        return countingApi;
    }

    private FatFileSystem create() throws Exception {
        return new FatFileSystemFormatter(ClusterSize._4Kb).format(fileDevice);
    }

    private FatFileSystem openReadOnly() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        fileDevice = new FileDevice(diskFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return (FatFileSystem) type.create(fileDevice, true);
    }

    private static final class FailingFileDevice extends FileDevice {
        private boolean armed;
        private int writesBeforeFail;

        private FailingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        private void armFailAfterWrites(int n) {
            armed = true;
            writesBeforeFail = n;
        }

        private void disarm() {
            armed = false;
        }

        @Override
        public void write(long devOffset, ByteBuffer srcBuf) throws IOException {
            if (armed) {
                if (writesBeforeFail == 0) {
                    armed = false;
                    throw new IOException("simulated device failure");
                }
                writesBeforeFail--;
            }
            super.write(devOffset, srcBuf);
        }
    }

    private static final class CountingFSBlockDeviceAPI implements FSBlockDeviceAPI {
        private final FileDevice delegate;
        private final AtomicLong writeCount = new AtomicLong();
        private final AtomicLong bytesWritten = new AtomicLong();
        private final AtomicLong largeWriteCount = new AtomicLong();
        private final AtomicLong largeBytesWritten = new AtomicLong();

        private CountingFSBlockDeviceAPI(FileDevice delegate) {
            this.delegate = delegate;
        }

        public long getLength() throws IOException {
            return delegate.getLength();
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
            delegate.read(devOffset, dest);
        }

        public void write(long devOffset, ByteBuffer src) throws IOException {
            writeCount.incrementAndGet();
            bytesWritten.addAndGet(src.remaining());
            if (src.remaining() >= 4096) {
                largeWriteCount.incrementAndGet();
                largeBytesWritten.addAndGet(src.remaining());
            }
            delegate.write(devOffset, src);
        }

        public void flush() throws IOException {
            delegate.flush();
        }

        public int getSectorSize() throws IOException {
            return delegate.getSectorSize();
        }

        public org.jnode.partitions.PartitionTableEntry getPartitionTableEntry() {
            return delegate.getPartitionTableEntry();
        }

        long writeCount() {
            return writeCount.get();
        }

        long bytesWritten() {
            return bytesWritten.get();
        }

        long largeWriteCount() {
            return largeWriteCount.get();
        }

        long largeBytesWritten() {
            return largeBytesWritten.get();
        }

        void resetWriteStats() {
            writeCount.set(0);
            bytesWritten.set(0);
            largeWriteCount.set(0);
            largeBytesWritten.set(0);
        }
    }
}
