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
import java.util.Arrays;
import java.util.List;

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
 * is not walked twice.
 *
 * The entry length is the authority for length everywhere else in JFAT, so
 * the count passed down comes from the directory entry while the chain
 * itself is what the single-argument free(int) walks. The two can disagree
 * (a length extended without a matching chain append, i.e. the write-failure
 * shapes). The decided policy, pinned here:
 *
 *   1. No desync: behavior is identical to free(int) (same FAT image).
 *   2. Chain shorter than the entry, cut point still reachable: the chain is
 *      cut at the entry-implied new length; only clusters at index
 *      >= newClusters are freed (fewer than n clusters may be freed).
 *   3. Chain shorter than the entry, cut point unreachable (or exactly one
 *      cluster past the end of the actual chain): no FAT entry is modified
 *      and an IOException is thrown, mirroring the zero-side-effect failure
 *      of free(int).
 *   4. Chain longer than the entry: clusters at index >= newClusters are all
 *      freed, including the surplus beyond the entry-implied old length
 *      (more than n clusters); clusters before the cut are never modified.
 *   5. Truncate to zero with chainClusters == n: the whole actual chain is
 *      freed and the start cluster is reset to 0.
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
    public void testDesyncChainShorterThanEntryCutReachable() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "short.bin", 4);
        List<Integer> chain = chainClusters(((FatEntry) file).getChain());
        assertEquals(4, chain.size());

        // write-failure shape: a length extended without a matching chain
        // append, i.e. the entry claims 10 clusters but only 4 exist
        forceEntryLength(file, 10L * fs.getClusterSize());

        file.setLength(2L * fs.getClusterSize());

        assertEquals(2L * fs.getClusterSize(), file.getLength());
        FatChain chainObj = ((FatEntry) file).getChain();
        assertEquals(2, chainObj.size());
        Fat fat = fs.getFat();
        assertTrue("cluster at index 2 must be freed", fat.isFreeEntry(chain.get(2)));
        assertTrue("cluster at index 3 must be freed", fat.isFreeEntry(chain.get(3)));
        assertFalse("cluster at index 0 must be kept", fat.isFreeEntry(chain.get(0)));
        assertFalse("cluster at index 1 must be kept", fat.isFreeEntry(chain.get(1)));
        assertArrayEquals(Arrays.copyOf(pattern(4 * fs.getClusterSize()), 2 * fs.getClusterSize()),
            readAll(file));
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
            // nothing may be freed
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
    public void testDesyncChainLongerThanEntryReclaimsSurplus() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "long.bin", 10);
        List<Integer> chain = chainClusters(((FatEntry) file).getChain());
        assertEquals(10, chain.size());

        // chain holds 10 clusters, the entry claims 4
        forceEntryLength(file, 4L * fs.getClusterSize());

        file.setLength(2L * fs.getClusterSize());

        assertEquals(2L * fs.getClusterSize(), file.getLength());
        FatChain chainObj = ((FatEntry) file).getChain();
        assertEquals(2, chainObj.size());
        Fat fat = fs.getFat();
        assertFalse("cluster at index 0 must be kept", fat.isFreeEntry(chain.get(0)));
        assertFalse("cluster at index 1 must be kept", fat.isFreeEntry(chain.get(1)));
        assertTrue("cluster at index 2 must be freed", fat.isFreeEntry(chain.get(2)));
        assertTrue("cluster at index 3 must be freed", fat.isFreeEntry(chain.get(3)));
        // the surplus beyond the entry-implied old length is reclaimed as
        // well: more than n clusters are freed, never anything before the cut
        assertTrue("surplus cluster at index 4 must be freed", fat.isFreeEntry(chain.get(4)));
        assertTrue("surplus cluster at index 5 must be freed", fat.isFreeEntry(chain.get(5)));
        assertTrue("surplus cluster at index 6 must be freed", fat.isFreeEntry(chain.get(6)));
        assertTrue("surplus cluster at index 7 must be freed", fat.isFreeEntry(chain.get(7)));
        assertTrue("surplus cluster at index 8 must be freed", fat.isFreeEntry(chain.get(8)));
        assertTrue("surplus cluster at index 9 must be freed", fat.isFreeEntry(chain.get(9)));
        assertArrayEquals(Arrays.copyOf(pattern(10 * fs.getClusterSize()), 2 * fs.getClusterSize()),
            readAll(file));
        fs.close();
    }

    @Test
    public void testDesyncTruncateToZeroFreesWholeActualChain() throws Exception {
        FatFileSystem fs = formatImage(diskFile);
        FSFile file = createChainFile(fs, "zero.bin", 2);
        List<Integer> chain = chainClusters(((FatEntry) file).getChain());

        // entry claims 10 clusters, chain holds 2; chainClusters == n
        forceEntryLength(file, 10L * fs.getClusterSize());

        file.setLength(0);

        assertEquals(0, file.getLength());
        assertEquals("start cluster must be reset to 0", 0, ((FatEntry) file).getStartCluster());
        Fat fat = fs.getFat();
        assertTrue("cluster at index 0 must be freed", fat.isFreeEntry(chain.get(0)));
        assertTrue("cluster at index 1 must be freed", fat.isFreeEntry(chain.get(1)));
        ByteBuffer empty = ByteBuffer.allocate(1024);
        file.read(0, empty);
        assertEquals(0, empty.position());
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

    private List<Integer> chainClusters(FatChain chain) throws Exception {
        List<Integer> clusters = new ArrayList<Integer>();
        FatChain.ChainIterator iterator = chain.listIterator();
        while (iterator.hasNext()) {
            clusters.add(iterator.next());
        }
        return clusters;
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
