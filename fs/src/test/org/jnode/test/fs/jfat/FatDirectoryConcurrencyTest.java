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
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regression test for issue #914: one directory monitor must cover the
 * children/idMap caches and every scan-and-publish path, and FatTable must
 * not share a mutable lookup key across concurrent get() calls.
 *
 * Two threads hammer ONE directory: a synchronized-read thread and an
 * unsynchronized collide/iterator path. Before the fix this threw
 * IllegalArgumentException("shouldn't happen") from FatTable.put via
 * FatDirectory.getEntry, and occasionally read a torn short entry
 * (FstClusHi too large) via remove -> isEmpty.
 */
public class FatDirectoryConcurrencyTest {

    private static final long READER_COLLIDE_DURATION_MILLIS = 10000L;
    private static final long CACHE_STRESS_DURATION_MILLIS = 5000L;

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
        diskFile = File.createTempFile("jfat-dir-concurrency-test", ".img");
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
    public void testConcurrentGetEntryAndCollideOnSharedDirectory() throws Exception {
        fileDevice = new SynchronizedFileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        final FSDirectory shared = root.addDirectory("shared").getDirectory();
        shared.addFile("alpha.txt");
        shared.addFile("beta.txt");

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicLong entryReads = new AtomicLong();
        final AtomicLong collides = new AtomicLong();

        Thread reader = new Thread(new Runnable() {
            public void run() {
                while (!stop.get()) {
                    try {
                        FSEntry entry = shared.getEntry("alpha.txt");
                        if (entry == null) {
                            failure.compareAndSet(null,
                                new IllegalStateException("getEntry returned null for alpha.txt"));
                            return;
                        }
                        entryReads.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "fat-dir-get-entry");

        Thread collider = new Thread(new Runnable() {
            public void run() {
                while (!stop.get()) {
                    try {
                        FSDirectory dir = (FSDirectory) shared;
                        boolean collidesName =
                            ((org.jnode.fs.jfat.FatDirectory) dir).collide("beta.txt");
                        if (!collidesName) {
                            failure.compareAndSet(null,
                                new IllegalStateException("collide returned false for beta.txt"));
                            return;
                        }
                        collides.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "fat-dir-collide");

        reader.start();
        collider.start();
        Thread.sleep(READER_COLLIDE_DURATION_MILLIS);
        stop.set(true);
        reader.join(30000L);
        collider.join(30000L);

        assertNull("reader/collider threw: " + failure.get(), failure.get());
        assertTrue("getEntry made no progress", entryReads.get() > 0);
        assertTrue("collide made no progress", collides.get() > 0);

        fs.close();
    }

    @Test
    public void testConcurrentCreateRemoveAndIterateOnSharedDirectory() throws Exception {
        fileDevice = new SynchronizedFileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        final FSDirectory shared = root.addDirectory("churn").getDirectory();
        shared.addFile("keep.txt");

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicLong operations = new AtomicLong();

        Runnable churn = new Runnable() {
            private int counter = 0;

            public void run() {
                while (!stop.get()) {
                    String name = "churn-" + Thread.currentThread().getName() + "-" + (counter++) + ".txt";
                    try {
                        FSFile file = (FSFile) shared.addFile(name);
                        file.write(0, ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
                        file.flush();
                        shared.remove(name);
                        operations.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        };

        Thread churnA = new Thread(churn, "a");
        Thread churnB = new Thread(churn, "b");

        Thread scanner = new Thread(new Runnable() {
            public void run() {
                while (!stop.get()) {
                    try {
                        Iterator<? extends FSEntry> i = shared.iterator();
                        while (i.hasNext()) {
                            i.next();
                        }
                        ((org.jnode.fs.jfat.FatDirectory) shared).isEmpty();
                        operations.incrementAndGet();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }
        }, "scanner");

        churnA.start();
        churnB.start();
        scanner.start();
        Thread.sleep(CACHE_STRESS_DURATION_MILLIS);
        stop.set(true);
        churnA.join(30000L);
        churnB.join(30000L);
        scanner.join(30000L);

        assertNull("churn/scanner threw: " + failure.get(), failure.get());
        assertTrue("no progress was made", operations.get() > 0);
        assertNotNull("keep.txt disappeared", shared.getEntry("keep.txt"));

        fs.close();
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
