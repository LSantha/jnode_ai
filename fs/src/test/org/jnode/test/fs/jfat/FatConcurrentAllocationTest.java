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

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
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
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatConcurrentAllocationTest {

    private File diskFile;
    private FileDevice device;
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
        diskFile = File.createTempFile("jfat-concurrent-alloc", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
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

    /*
     * Every worker rewinds the allocation hint to the first data cluster and
     * then allocates and releases a single cluster. All threads therefore
     * scan for the same lowest free cluster: the scan-and-claim sequence must
     * be atomic with respect to the other chains on the same FAT, otherwise
     * two chains claim the same cluster, the free-cluster counter loses a
     * cluster and no chain owns it. With the fix this can never happen;
     * without it the final counter-versus-scan comparison fails.
     */
    @Test
    public void testConcurrentAllocFreeKeepsFreeClusterCount() throws Exception {
        device = new FileDevice(diskFile, "rw");
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        final FatFileSystem fs = formatter.format(device);
        final Fat fat = fs.getFat();
        final int mountCount = fat.getFreeClusterCount();
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(4);
        final java.util.concurrent.atomic.AtomicInteger errors =
            new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    try {
                        FatChain chain = new FatChain(fs, 0);
                        for (int i = 0; i < 128; i++) {
                            barrier.await();
                            fat.rewindFree();
                            chain.allocateAndClear(1);
                            chain.freeAllClusters();
                        }
                    } catch (Throwable th) {
                        errors.incrementAndGet();
                    }
                }
            });
        }
        for (int t = 0; t < threads.length; t++) {
            threads[t].start();
        }
        for (int t = 0; t < threads.length; t++) {
            threads[t].join();
        }
        assertEquals("workers must not fail", 0, errors.get());
        assertEquals("free-cluster counter must be restored after balanced alloc/free cycles",
            mountCount, fat.getFreeClusterCount());
        assertEquals("free-cluster counter must match a fresh FAT scan",
            fat.freeEntries(), fat.getFreeClusterCount());
        fs.close();
    }
}
