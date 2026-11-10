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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
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
 * Regression test for issue #910: FatChain used to share one ChainPosition
 * and one ChainIterator across all operations, so a reader and a writer
 * interleaving on the same file (and therefore on the same cached FatEntry
 * and FatChain) corrupted each other's traversal state.
 */
public class FatChainConcurrencyTest {

    private static final int FILE_LENGTH = 204800;
    private static final int READER_REGION_SIZE = 32 * 1024;
    private static final int WRITER_REGION_OFFSET = 128 * 1024;
    private static final int WRITER_REGION_SIZE = 32 * 1024;
    private static final long DURATION_MILLIS = 5000L;

    private static final int GROW_INITIAL_LENGTH = 8192;
    private static final int GROW_CHUNK_BYTES = 1024;
    private static final int GROW_MAX_APPEND_BYTES = 192 * 1024;

    private File diskFile;
    private FileDevice fileDevice;
    private FileSystemService fss;

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
        diskFile = File.createTempFile("jfat-chain-concurrency-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            try {
                fileDevice.close();
            } catch (Exception e) {
            }
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testConcurrentReaderWriterOnSameFile() throws Exception {
        fileDevice = new SynchronizedFileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("stress.bin").getFile();

        byte[] initial = new byte[FILE_LENGTH];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = readerByte(i);
        }
        file.write(0, ByteBuffer.wrap(initial));
        file.flush();

        final FSFile shared = file;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final AtomicLong corruptedReads = new AtomicLong();

        Thread reader = new Thread(new Runnable() {
            public void run() {
                ByteBuffer buf = ByteBuffer.allocate(READER_REGION_SIZE);
                while (!stop.get()) {
                    try {
                        buf.clear();
                        shared.read(0, buf);
                        buf.flip();
                        for (int i = 0; i < READER_REGION_SIZE; i++) {
                            if (buf.get() != readerByte(i)) {
                                corruptedReads.incrementAndGet();
                                return;
                            }
                        }
                        reads.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "fat-chain-reader");

        Thread writer = new Thread(new Runnable() {
            public void run() {
                byte[] payload = new byte[WRITER_REGION_SIZE];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = writerByte(i);
                }
                while (!stop.get()) {
                    try {
                        shared.write(WRITER_REGION_OFFSET, ByteBuffer.wrap(payload));
                        writes.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "fat-chain-writer");

        reader.start();
        writer.start();
        Thread.sleep(DURATION_MILLIS);
        stop.set(true);
        reader.join(30000L);
        writer.join(30000L);

        assertNull("reader/writer threw: " + failure.get(), failure.get());
        assertEquals("corrupted reads", 0, corruptedReads.get());
        assertTrue("reader made no progress", reads.get() > 0);
        assertTrue("writer made no progress", writes.get() > 0);

        ByteBuffer verify = ByteBuffer.allocate(READER_REGION_SIZE);
        file.read(0, verify);
        verify.flip();
        byte[] expected = Arrays.copyOf(initial, READER_REGION_SIZE);
        byte[] actual = new byte[READER_REGION_SIZE];
        verify.get(actual);
        assertTrue("reader region changed", Arrays.equals(expected, actual));

        fs.close();
    }

    @Test
    public void testWriterAppendsWhileReaderLoops() throws Exception {
        fileDevice = new SynchronizedFileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("grow.bin").getFile();

        byte[] initial = new byte[GROW_INITIAL_LENGTH];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = readerByte(i);
        }
        file.write(0, ByteBuffer.wrap(initial));
        file.flush();

        final FSFile shared = file;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong appends = new AtomicLong();
        final AtomicLong shortReads = new AtomicLong();
        final AtomicLong corruptedReads = new AtomicLong();

        Thread reader = new Thread(new Runnable() {
            public void run() {
                while (!stop.get()) {
                    try {
                        long len = shared.getLength();
                        if (len < GROW_INITIAL_LENGTH) {
                            continue;
                        }
                        ByteBuffer buf = ByteBuffer.allocate((int) len);
                        shared.read(0, buf);
                        if (buf.position() != len) {
                            shortReads.incrementAndGet();
                            return;
                        }
                        buf.flip();
                        for (int i = 0; i < GROW_INITIAL_LENGTH; i++) {
                            if (buf.get() != readerByte(i)) {
                                corruptedReads.incrementAndGet();
                                return;
                            }
                        }
                        reads.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "fat-chain-grow-reader");

        Thread writer = new Thread(new Runnable() {
            public void run() {
                long offset = GROW_INITIAL_LENGTH;
                while (!stop.get() && offset < GROW_INITIAL_LENGTH + GROW_MAX_APPEND_BYTES) {
                    try {
                        byte[] payload = new byte[GROW_CHUNK_BYTES];
                        for (int j = 0; j < payload.length; j++) {
                            payload[j] = writerByte((int) (offset + j));
                        }
                        shared.write(offset, ByteBuffer.wrap(payload));
                        offset += GROW_CHUNK_BYTES;
                        appends.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
                stop.set(true);
            }
        }, "fat-chain-append-writer");

        reader.start();
        writer.start();
        writer.join(60000L);
        stop.set(true);
        reader.join(60000L);

        assertNull("reader/writer threw: " + failure.get(), failure.get());
        assertEquals("short reads", 0, shortReads.get());
        assertEquals("corrupted reads", 0, corruptedReads.get());
        assertTrue("writer made no progress", appends.get() > 0);
        assertTrue("reader made no progress", reads.get() > 0);
        assertEquals("chain must contain the appended data",
            GROW_INITIAL_LENGTH + (long) appends.get() * GROW_CHUNK_BYTES, file.getLength());

        fs.close();
    }

    private static byte readerByte(int i) {
        return (byte) (i & 0xFF);
    }

    private static byte writerByte(int i) {
        return (byte) ((i * 7 + 13) & 0xFF);
    }

    static class SynchronizedFileDevice extends FileDevice {
        SynchronizedFileDevice(File file, String mode) throws java.io.IOException {
            super(file, mode);
        }

        @Override
        public synchronized void read(long devOffset, ByteBuffer destBuf) throws java.io.IOException {
            super.read(devOffset, destBuf);
        }

        @Override
        public synchronized void write(long devOffset, ByteBuffer srcBuf) throws java.io.IOException {
            super.write(devOffset, srcBuf);
        }
    }
}
