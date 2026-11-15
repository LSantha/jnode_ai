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
 *
 */

package org.jnode.test.fs.jfat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatEntry;
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
 * Pins the behavior of the cached cluster count and tail maintained by
 * FatChain: size()/getEndCluster() must stay correct (and O(1)) across the
 * mutations the chain itself performs, and the debug walk-vs-cached double
 * check (FatChain.debugSizeCacheCheck) must not fire on any of those paths.
 * The rollback shapes exercise the invalidation on the failure path.
 */
public class FatChainSizeCacheTest {

    private static class FailingFileDevice extends FileDevice {
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

    private File diskFile;
    private FailingFileDevice fileDevice;
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
        FatChain.debugSizeCacheCheck = true;
        diskFile = File.createTempFile("jfat-size-cache-test", ".img");
        diskFile.deleteOnExit();
        fileDevice = new FailingFileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
    }

    @After
    public void tearDown() throws Exception {
        FatChain.debugSizeCacheCheck = false;
        if (fileDevice != null) {
            try {
                fileDevice.close();
            } catch (Exception e) {
            }
            fileDevice = null;
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testSizeCachedAcrossWritesAndTruncate() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("cache.bin").getFile();
        byte[] data = pattern(10 * fs.getClusterSize());
        file.write(0, ByteBuffer.wrap(data));
        file.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals("chain length after write", 10, chain.size());

        file.setLength(4L * fs.getClusterSize());
        assertEquals("chain length after truncate", 4, chain.size());
        file.setLength(6L * fs.getClusterSize());

        assertEquals("chain length after rewrite", 6, chain.size());

        file.setLength(0);
        assertEquals("chain length after truncate to zero", 0, chain.size());
        assertEquals("start cluster must be reset", 0, chain.getStartCluster());
        fs.close();
    }

    @Test
    public void testAllocateAndClearAdvancesCachedCount() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("grow.bin").getFile();
        byte[] data = pattern(3 * fs.getClusterSize());
        file.write(0, ByteBuffer.wrap(data));
        file.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(3, chain.size());

        chain.allocateAndClear(2);
        assertEquals("allocateAndClear must grow the cached count", 5, chain.size());

        chain.allocateAndClear(1);
        assertEquals("allocateAndClear must grow the cached count again", 6, chain.size());
        fs.close();
    }

    @Test
    public void testFreeAllClustersResetsCachedCount() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("all.bin").getFile();
        file.write(0, ByteBuffer.wrap(pattern(5 * fs.getClusterSize())));
        file.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(5, chain.size());

        chain.freeAllClusters();
        assertEquals("chain must be empty after freeAllClusters", 0, chain.size());
        assertEquals("start cluster must be reset", 0, chain.getStartCluster());
        fs.close();
    }

    @Test
    public void testFailedWriteRollbackKeepsCacheConsistent() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("leak.bin").getFile();
        byte[] first = pattern(fs.getClusterSize());
        file.write(0, ByteBuffer.wrap(first));
        file.flush();
        fs.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(1, chain.size());

        byte[] extra = pattern(2 * fs.getClusterSize());
        fileDevice.armFailAfterWrites(0);
        try {
            file.write(fs.getClusterSize(), ByteBuffer.wrap(extra));
            fail("expected IOException from failing device");
        } catch (IOException expected) {
        }

        assertEquals("rolled-back chain must report its pre-failure length", 1, chain.size());

        fileDevice.disarm();
        byte[] retry = pattern(fs.getClusterSize());
        file.write(fs.getClusterSize(), ByteBuffer.wrap(retry));
        file.flush();
        assertEquals("chain must grow after a successful retry", 2, chain.size());
        fs.close();
    }

    @Test
    public void testFailedWriteToNewFileRollbackKeepsCacheConsistent() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("new.bin").getFile();
        file.flush();
        fs.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(0, chain.size());

        byte[] extra = pattern(2 * fs.getClusterSize());
        fileDevice.armFailAfterWrites(0);
        try {
            file.write(0, ByteBuffer.wrap(extra));
            fail("expected IOException from failing device");
        } catch (IOException expected) {
        }

        assertEquals("rolled-back chain must be empty", 0, chain.size());
        assertEquals("start cluster must stay reset after rollback", 0, chain.getStartCluster());

        fileDevice.disarm();
        byte[] retry = pattern(fs.getClusterSize());
        file.write(0, ByteBuffer.wrap(retry));
        file.flush();
        assertEquals("chain must hold the retry's cluster", 1, chain.size());
        fs.close();
    }

    @Test
    public void testDebugCheckThrowsOnStaleCachedCount() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("stale.bin").getFile();
        file.write(0, ByteBuffer.wrap(pattern(3 * fs.getClusterSize())));
        file.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(3, chain.size());

        setCachedInt(chain, "cachedClusterCount", 999);
        try {
            chain.size();
            fail("expected IllegalStateException for a stale cached count");
        } catch (IllegalStateException expected) {
            assertTrue("message must name the count mismatch",
                expected.getMessage().contains("length cache mismatch"));
        }

        setCachedInt(chain, "cachedClusterCount", -1);
        setCachedInt(chain, "cachedTailCluster", -1);
        assertEquals("reset cache must fall back to a fresh walk", 3, chain.size());
        fs.close();
    }

    @Test
    public void testDebugCheckThrowsOnStaleCachedTail() throws Exception {
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
        FSFile file = fs.getRootEntry().getDirectory().addFile("staletail.bin").getFile();
        file.write(0, ByteBuffer.wrap(pattern(3 * fs.getClusterSize())));
        file.flush();

        FatChain chain = ((FatEntry) file).getChain();
        assertEquals(3, chain.size());

        setCachedInt(chain, "cachedTailCluster", 1);
        setCachedInt(chain, "cachedClusterCount", -1);
        try {
            chain.size();
            fail("expected IllegalStateException for a stale cached tail");
        } catch (IllegalStateException expected) {
            assertTrue("message must name the tail mismatch",
                expected.getMessage().contains("tail cache mismatch"));
        }

        setCachedInt(chain, "cachedTailCluster", -1);
        assertEquals("reset cache must fall back to a fresh walk", 3, chain.size());
        fs.close();
    }

    private static void setCachedInt(FatChain chain, String field, int value) throws Exception {
        Field f = FatChain.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(chain, value);
    }

    private byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }
}
