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
import java.util.Arrays;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
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
 * Pins the behavior of the count-accepting FatChain.free(int, int) overload,
 * which is what FatFile.freeClusters uses on the truncate path so the chain
 * does not walk itself a second time to count it.
 *
 * The core contract is walk parity: free(int, chainClusters) always frees
 * exactly the set free(int) would free - the last n clusters measured from
 * the actual chain end, never fewer, never more - and fails the same way
 * (IOException, no FAT entry modified) when the chain holds fewer than n
 * clusters. The entry-derived count is used as a hint to locate the
 * entry-implied cut point; the tail from that point to end of chain is
 * scanned WITHOUT writing, and on any mismatch (unreachable or missing cut
 * point, tail length != n) the method defers entirely to the walk. Every
 * desync path therefore leaves the FAT untouched until the tail is known
 * to match, and the freed-set parity with free(int) holds in all shapes.
 *
 * The decided policy, pinned here:
 *
 *   1. No desync: behavior is identical to free(int) (same FAT image).
 *   2. Chain shorter than the entry (any cut point): defers to the walk;
 *      free(n) throws IOException because the chain holds fewer than n
 *      clusters; no FAT entry is modified.
 *   3. Boundary desync: the entry-implied cut point is exactly one cluster
 *      past the end of the actual chain; an IOException is thrown (never a
 *      NoSuchElementException) and no FAT entry is modified.
 *   4. Chain longer than the entry: defers to the walk; exactly the actual
 *      last n clusters are freed (the surplus beyond the entry-implied old
 *      length is NOT reclaimed, reproducing free(int)'s behavior); the FAT
 *      image is byte-identical to calling free(int) on an identically
 *      formatted image.
 *   5. Truncate to zero under desync: defers to the walk; identical freed
 *      set and failure mode to free(int).
 */
public class FatChainFreeWithCountTest {

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
        diskFile = File.createTempFile("jfat-free-count-test", ".img");
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
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testCountedFreeMatchesWalkClusterForCluster() throws Exception {
        File imageB = File.createTempFile("jfat-free-count-test-b", ".img");
        imageB.deleteOnExit();
        FileDevice deviceB = null;
        FatFileSystem fsB = null;
        try {
            FatFileSystem fsA = formatImage(diskFile);
            deviceB = new FileDevice(imageB, "rw");
            deviceB.setLength(68L * 1024 * 1024);
            fsB = new FatFileSystemFormatter(ClusterSize._1Kb).format(deviceB);

            FSFile fileA = createChainFile(fsA, "match.bin", 6);
            FSFile fileB = createChainFile(fsB, "match.bin", 6);

            FatChain chainA = ((FatEntry) fileA).getChain();
            FatChain chainB = ((FatEntry) fileB).getChain();

            chainA.free(2);
            chainB.free(2, 6);

            assertEquals("start cluster must match", chainA.getStartCluster(), chainB.getStartCluster());
            assertEquals("remaining chain length must match", chainA.size(), chainB.size());
            assertArrayEquals("FAT must be byte-identical after the walk and counted frees",
                snapshotFat(fsA.getFat()), snapshotFat(fsB.getFat()));
            fsA.close();
        } finally {
            if (fsB != null) {
                try {
                    fsB.close();
                } catch (Exception e) {
                }
            }
            if (deviceB != null) {
                try {
                    deviceB.close();
                } catch (Exception e) {
                }
            }
            imageB.delete();
        }
    }

    @Test
    public void testDesyncChainShorterThanEntryDefersToWalkFailsClosed() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "short.bin", 4);
        int head = ((FatEntry) file).getStartCluster();

        // write-failure shape: a length extended without a matching chain
        // append, i.e. the entry claims 10 clusters but only 4 exist
        forceEntryLength(file, 10L * fs.getClusterSize());

        int[] before = snapshotFat(fs.getFat());

        // n would be 8; free(8) on a 4-cluster chain must throw and free
        // nothing, so the counted overload defers to the walk and fails the
        // same way: IOException, no FAT entry modified
        try {
            file.setLength(2L * fs.getClusterSize());
            fail("expected IOException when the chain holds fewer clusters than n");
        } catch (IOException expected) {
        }

        assertArrayEquals("no FAT entry may be modified under desync",
            before, snapshotFat(fs.getFat()));
        assertEquals("chain head must be unchanged", head, ((FatEntry) file).getStartCluster());
        assertEquals("chain must be intact", 4, ((FatEntry) file).getChain().size());
        assertEquals("entry length must be unchanged", 10L * fs.getClusterSize(), file.getLength());
        fs.close();
    }

    @Test
    public void testDesyncChainShorterThanEntryCutUnreachable() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "under.bin", 2);
        int head = ((FatEntry) file).getStartCluster();

        // entry claims 10 clusters, chain holds 2
        forceEntryLength(file, 10L * fs.getClusterSize());

        int[] before = snapshotFat(fs.getFat());
        try {
            // entry-implied cut lands at index 4, beyond the 2-cluster chain:
            // the overload defers to the walk, and free(n) fails on a chain
            // shorter than n - nothing may be freed
            file.setLength(5L * fs.getClusterSize());
            fail("expected IOException when the entry-implied cut point is unreachable");
        } catch (IOException expected) {
        }

        assertArrayEquals("no FAT entry may be modified when the cut point is unreachable",
            before, snapshotFat(fs.getFat()));
        assertEquals("chain head must be unchanged", head, ((FatEntry) file).getStartCluster());
        assertEquals("chain must be intact", 2, ((FatEntry) file).getChain().size());
        assertEquals("entry length must be unchanged", 10L * fs.getClusterSize(), file.getLength());
        fs.close();
    }

    @Test
    public void testDesyncBoundaryCutExactlyOneClusterPastEnd() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "boundary.bin", 2);
        int head = ((FatEntry) file).getStartCluster();

        // chain holds 2 clusters, the entry claims 10; the entry-implied cut
        // (index chainClusters - n - 1 == 2) is exactly one cluster past the
        // end of the actual chain, where the iterator is positioned at EOF
        forceEntryLength(file, 10L * fs.getClusterSize());

        int[] before = snapshotFat(fs.getFat());
        try {
            file.setLength(3L * fs.getClusterSize());
            fail("expected IOException when the entry-implied cut point is one cluster past the end");
        } catch (IOException expected) {
            // an IOException, never a NoSuchElementException
        }

        assertArrayEquals("no FAT entry may be modified at the boundary cut",
            before, snapshotFat(fs.getFat()));
        assertEquals("chain head must be unchanged", head, ((FatEntry) file).getStartCluster());
        assertEquals("chain must be intact", 2, ((FatEntry) file).getChain().size());
        assertEquals("entry length must be unchanged", 10L * fs.getClusterSize(), file.getLength());
        fs.close();
    }

    @Test
    public void testDesyncChainLongerThanEntryMatchesWalk() throws Exception {
        // image A: the truncate goes through FatFile.setLength -> counted
        // overload. image B: the same desync is forced and the single-argument
        // free(int) is called directly. Walk parity pins the counted overload to
        // free exactly the set free(n) frees: the actual last n clusters, never
        // fewer, never more.
        File imageB = File.createTempFile("jfat-free-count-test-b", ".img");
        imageB.deleteOnExit();
        FileDevice deviceB = null;
        FatFileSystem fsB = null;
        try {
            FatFileSystem fsA = formatImage(diskFile);
            deviceB = new FileDevice(imageB, "rw");
            deviceB.setLength(68L * 1024 * 1024);
            fsB = new FatFileSystemFormatter(ClusterSize._1Kb).format(deviceB);

            FSFile fileA = createChainFile(fsA, "long.bin", 6);
            FSFile fileB = createChainFile(fsB, "long.bin", 6);

            // chain holds 6 clusters, the entry claims 4
            forceEntryLength(fileA, 4L * fsA.getClusterSize());
            forceEntryLength(fileB, 4L * fsB.getClusterSize());

            fileA.setLength(2L * fsA.getClusterSize());
            ((FatEntry) fileB).getChain().free(2);

            assertEquals("start cluster must match the walk",
                ((FatEntry) fileB).getStartCluster(), ((FatEntry) fileA).getStartCluster());
            assertEquals("remaining chain length must match the walk",
                ((FatEntry) fileB).getChain().size(), ((FatEntry) fileA).getChain().size());
            assertArrayEquals("FAT must be byte-identical to the walk's FAT",
                snapshotFat(fsB.getFat()), snapshotFat(fsA.getFat()));
            assertEquals("entry length must be the new truncated length",
                2L * fsA.getClusterSize(), fileA.getLength());
            assertArrayEquals(Arrays.copyOf(pattern(6 * fsA.getClusterSize()), 2 * fsA.getClusterSize()),
                readAll(fileA));
            fsA.close();
        } finally {
            if (fsB != null) {
                try {
                    fsB.close();
                } catch (Exception e) {
                }
            }
            if (deviceB != null) {
                try {
                    deviceB.close();
                } catch (Exception e) {
                }
            }
            imageB.delete();
        }
    }

    @Test
    public void testDesyncTruncateToZeroMatchesWalk() throws Exception {
        // image A: the truncate goes through FatFile.setLength -> counted
        // overload. image B: the same desync is forced and the single-argument
        // free(int) is called directly. Walk parity pins the counted overload to
        // free exactly the set free(n) frees.
        File imageB = File.createTempFile("jfat-free-count-test-b", ".img");
        imageB.deleteOnExit();
        FileDevice deviceB = null;
        FatFileSystem fsB = null;
        try {
            FatFileSystem fsA = formatImage(diskFile);
            deviceB = new FileDevice(imageB, "rw");
            deviceB.setLength(68L * 1024 * 1024);
            fsB = new FatFileSystemFormatter(ClusterSize._1Kb).format(deviceB);

            FSFile fileA = createChainFile(fsA, "zero.bin", 6);
            FSFile fileB = createChainFile(fsB, "zero.bin", 6);

            // chain holds 6 clusters, the entry claims 4
            forceEntryLength(fileA, 4L * fsA.getClusterSize());
            forceEntryLength(fileB, 4L * fsB.getClusterSize());

            fileA.setLength(0);
            ((FatEntry) fileB).getChain().free(4);

            assertEquals("start cluster must match the walk",
                ((FatEntry) fileB).getStartCluster(), ((FatEntry) fileA).getStartCluster());
            assertEquals("remaining chain length must match the walk",
                ((FatEntry) fileB).getChain().size(), ((FatEntry) fileA).getChain().size());
            assertArrayEquals("FAT must be byte-identical to the walk's FAT",
                snapshotFat(fsB.getFat()), snapshotFat(fsA.getFat()));
            assertEquals("entry length must be 0", 0, fileA.getLength());
            fsA.close();
        } finally {
            if (fsB != null) {
                try {
                    fsB.close();
                } catch (Exception e) {
                }
            }
            if (deviceB != null) {
                try {
                    deviceB.close();
                } catch (Exception e) {
                }
            }
            imageB.delete();
        }
    }

    @Test
    public void testDesyncTruncateToZeroChainShorterThanEntryFailsClosed() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "zeroshort.bin", 2);
        int head = ((FatEntry) file).getStartCluster();

        // chain holds 2 clusters, the entry claims 10; even a truncate to zero
        // defers to the walk, and free(10) on a 2-cluster chain throws
        forceEntryLength(file, 10L * fs.getClusterSize());

        int[] before = snapshotFat(fs.getFat());
        try {
            file.setLength(0);
            fail("expected IOException when the chain holds fewer clusters than n");
        } catch (IOException expected) {
        }

        assertArrayEquals("no FAT entry may be modified under desync",
            before, snapshotFat(fs.getFat()));
        assertEquals("chain head must be unchanged", head, ((FatEntry) file).getStartCluster());
        assertEquals("chain must be intact", 2, ((FatEntry) file).getChain().size());
        assertEquals("entry length must be unchanged", 10L * fs.getClusterSize(), file.getLength());
        fs.close();
    }

    private FatFileSystem formatImage(File image) throws Exception {
        FileDevice device = new FileDevice(image, "rw");
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        fileDevice = device;
        return formatter.format(device);
    }

    private FSFile createChainFile(FatFileSystem fs, String name, int clusterCount) throws Exception {
        int clusterSize = fs.getClusterSize();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile(name).getFile();
        file.write(0, ByteBuffer.wrap(pattern(clusterCount * clusterSize)));
        file.flush();
        return file;
    }

    private void forceEntryLength(FSFile file, long length) {
        ((FatEntry) file).getEntry().setLength(length);
    }

    private int[] snapshotFat(Fat fat) throws Exception {
        int[] snap = new int[fat.size()];
        for (int i = 0; i < fat.size(); i++) {
            snap[i] = fat.get(i);
        }
        return snap;
    }

    private byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
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
}
