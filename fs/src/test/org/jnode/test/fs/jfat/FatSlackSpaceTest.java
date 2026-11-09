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
import java.util.Iterator;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FSFileSlackSpace;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatDirectory;
import org.jnode.fs.jfat.FatFile;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.naming.InitialNaming;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the contract of {@link FatFile#getSlackSpace()}:
 * <ul>
 * <li>live entries return the bytes at the tail of their last cluster, read
 * through the chain - identical for contiguous and fragmented files;</li>
 * <li>deleted entries throw a descriptive {@link IOException} - the old code
 * computed a physical cluster as {@code startCluster + length / clusterSize},
 * which returned another file's bytes for fragmented files and threw
 * {@code IllegalArgumentException} for entries freed by JFAT itself.</li>
 * </ul>
 */
public class FatSlackSpaceTest {

    /** 68 MiB: comfortably above the formatter's enforced 64K-sector FAT32 floor. */
    private static final long IMAGE_LENGTH = 68L * 1024 * 1024;

    private static final int CLUSTER_SIZE = 1024;

    private FileSystemService fss;
    private File diskFile;
    private FileDevice fileDevice;

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
        diskFile = File.createTempFile("jfat-slackspace", ".img");
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

    /**
     * Live, contiguous file: the slack bytes match a chain read from the file
     * length offset inside the last cluster.
     */
    @Test
    public void testLiveContiguousSlackSpaceMatchesChainRead() throws Exception {
        FatFileSystem fs = formatAndOpen();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("contiguous.bin").getFile();

        byte[] data = new byte[2000];
        for (int i = 0; i < data.length; i++) {
            data[i] = patternByte(i);
        }
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        FatFile fatFile = (FatFile) file;
        int slackLength = CLUSTER_SIZE - (int) (data.length % CLUSTER_SIZE);
        byte[] slack = fatFile.getSlackSpace();

        Assert.assertEquals(slackLength, slack.length);
        Assert.assertArrayEquals(readSlackThroughChain(fatFile, data.length, slackLength), slack);
        fs.close();
    }

    /**
     * Live, fragmented file: A.bin gets its second cluster after B.bin was
     * allocated, so A.bin's chain is {@code [first, second]} with
     * {@code second != first + 1}. The slack must come from the chain's last
     * cluster - never from the cluster that sits between them (B.bin's).
     */
    @Test
    public void testLiveFragmentedSlackSpaceMatchesChainRead() throws Exception {
        FatFileSystem fs = formatAndOpen();
        FSDirectory root = fs.getRootEntry().getDirectory();

        FSFile aFile = root.addFile("a.bin").getFile();
        byte[] first = new byte[CLUSTER_SIZE];
        for (int i = 0; i < first.length; i++) {
            first[i] = patternByte(i);
        }
        aFile.write(0, ByteBuffer.wrap(first));
        aFile.flush();

        FSFile bFile = root.addFile("b.bin").getFile();
        byte[] bData = new byte[CLUSTER_SIZE];
        Arrays.fill(bData, (byte) 0xBB);
        bFile.write(0, ByteBuffer.wrap(bData));
        bFile.flush();

        byte[] tail = new byte[512];
        for (int i = 0; i < tail.length; i++) {
            tail[i] = (byte) (0x5A ^ (i * 13));
        }
        aFile.write(CLUSTER_SIZE, ByteBuffer.wrap(tail));
        aFile.flush();
        fs.flush();

        FatFile fatA = (FatFile) aFile;
        Fat fat = fs.getFat();
        int[] chain = chainClusters(fat, fatA.getChain().getStartCluster());
        Assert.assertEquals("a.bin should own exactly two clusters", 2, chain.length);
        Assert.assertFalse("a.bin chain must be fragmented (cluster " + chain[0] + " -> " + chain[1]
            + ") or this test proves nothing", chain[1] == chain[0] + 1);

        long length = fatA.getLength();
        Assert.assertEquals(1536L, length);
        int slackLength = CLUSTER_SIZE - (int) (length % CLUSTER_SIZE);

        byte[] slack = fatA.getSlackSpace();
        Assert.assertEquals(slackLength, slack.length);
        Assert.assertArrayEquals("slack must be the tail of the chain's last cluster",
            readSlackThroughChain(fatA, length, slackLength), slack);

        byte[] bSlack = new byte[slackLength];
        Arrays.fill(bSlack, (byte) 0xBB);
        Assert.assertFalse("slack must not return the bytes of B.bin's cluster",
            Arrays.equals(bSlack, slack));
        fs.close();
    }

    /**
     * A live file whose length is an exact multiple of the cluster size has no
     * slack bytes.
     */
    @Test
    public void testClusterAlignedLiveFileReturnsEmptySlackSpace() throws Exception {
        FatFileSystem fs = formatAndOpen();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("aligned.bin").getFile();

        byte[] data = new byte[CLUSTER_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = patternByte(i);
        }
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        byte[] slack = ((FSFileSlackSpace) file).getSlackSpace();
        Assert.assertEquals(0, slack.length);
        fs.close();
    }

    /**
     * Deleted entry (removed by JFAT itself, which zeroes the stored start
     * cluster): the slack space API must throw a descriptive IOException -
     * never a bare IllegalArgumentException, never bytes from another file -
     * and a post-remove() lookup through createIterator(true) / getEntryById
     * must not crash.
     */
    @Test
    public void testDeletedEntrySlackSpaceThrowsDescriptiveIOException() throws Exception {
        FatFileSystem fs = formatAndOpen();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile aFile = root.addFile("a.bin").getFile();

        byte[] first = new byte[CLUSTER_SIZE];
        for (int i = 0; i < first.length; i++) {
            first[i] = patternByte(i);
        }
        aFile.write(0, ByteBuffer.wrap(first));
        aFile.flush();
        FSFile bFile = root.addFile("b.bin").getFile();
        byte[] bData = new byte[CLUSTER_SIZE];
        Arrays.fill(bData, (byte) 0xBB);
        bFile.write(0, ByteBuffer.wrap(bData));
        bFile.flush();
        byte[] tail = new byte[512];
        aFile.write(CLUSTER_SIZE, ByteBuffer.wrap(tail));
        aFile.flush();
        fs.flush();

        root.remove("a.bin");
        fs.flush();
        fs.close();
        closeQuietly(fileDevice);
        fileDevice = null;

        FatFileSystem ro = openReadOnly();
        FSDirectory root2 = ro.getRootEntry().getDirectory();

        FatFile deleted = findDeletedFile(root2);
        Assert.assertNotNull("deleted a.bin should be visible via createIterator(true)", deleted);

        try {
            deleted.getSlackSpace();
            Assert.fail("expected IOException for a deleted entry");
        } catch (IOException e) {
            Assert.assertNotNull("IOException must carry a message", e.getMessage());
            Assert.assertTrue("IOException message should name the cause, got: " + e.getMessage(),
                e.getMessage().contains("deleted"));
        }

        FSEntry byId = root2.getEntryById(deleted.getId());
        Assert.assertNotNull("getEntryById must find the deleted entry", byId);
        ro.close();
    }

    private FatFile findDeletedFile(FSDirectory dir) throws IOException {
        Iterator<FSEntry> it = ((FatDirectory) dir).createIterator(true);
        while (it.hasNext()) {
            FSEntry e = it.next();
            if (e.isFile()) {
                FatFile ff = (FatFile) e.getFile();
                if (ff.getEntry().isFreeDirEntry()) {
                    return ff;
                }
            }
        }
        return null;
    }

    private byte[] readSlackThroughChain(FatFile file, long length, int slackLength) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(slackLength);
        file.getChain().read(length, buf);
        return buf.array();
    }

    private int[] chainClusters(Fat fat, int start) throws IOException {
        List<Integer> list = new ArrayList<Integer>();
        int cursor = start;
        while (cursor >= fat.firstCluster() && !fat.isEofChain(fat.get(cursor))) {
            list.add(Integer.valueOf(cursor));
            cursor = fat.get(cursor);
        }
        if (cursor >= fat.firstCluster()) {
            list.add(Integer.valueOf(cursor));
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).intValue();
        }
        return out;
    }

    private static byte patternByte(int i) {
        return (byte) (i * 31 + 7);
    }

    private FatFileSystem formatAndOpen() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_LENGTH);
        return new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);
    }

    private FatFileSystem openReadOnly() throws Exception {
        closeQuietly(fileDevice);
        fileDevice = new FileDevice(diskFile, "r");
        FileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return (FatFileSystem) type.create(fileDevice, true);
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
