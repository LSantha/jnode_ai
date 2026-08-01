/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
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
 
package org.jnode.test.fs.ext4;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jnode.driver.Device;
import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.ext2.Ext2Constants;
import org.jnode.fs.ext2.Ext2FileSystem;
import org.jnode.fs.ext2.Ext2FileSystemType;
import org.jnode.fs.ext2.Ext2Entry;
import org.jnode.fs.ext2.GroupDescriptor;
import org.jnode.fs.ext2.INode;
import org.jnode.fs.ext2.Superblock;
import org.jnode.fs.ext2.Ext2Utils;
import org.jnode.fs.ext4.Extent;
import org.jnode.fs.ext4.ExtentHeader;
import org.jnode.fs.service.FileSystemService;
import org.jnode.test.fs.DataStructureAsserts;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.AfterClass;
import org.junit.Test;

public class Ext4FileSystemTest {

    private Device device;
    private FileSystemService fss;
    private static final List<File> testFiles = new ArrayList<File>();

    @Before
    public void setUp() throws Exception {
        // create file system service.
        fss = FileSystemTestUtils.createFSService(Ext2FileSystemType.class.getName());
    }

    @Test
    public void testReadExt4SpecialFiles() throws Exception {

        device = new FileDevice(FileSystemTestUtils.getTestFile("test/fs/ext4/test-special-files.ext4"), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        String expectedStructure =
            "type: EXT2 vol: total:15728640 free:13918208\n" +
                "  /; \n" +
                "    lost+found; \n" +
                "    console; 0; d41d8cd98f00b204e9800998ecf8427e\n" +
                "    fifo; 0; d41d8cd98f00b204e9800998ecf8427e\n" +
                "    sda1; 0; d41d8cd98f00b204e9800998ecf8427e\n" +
                "    The_Rabies_Virus_Remains_a_Medical_Mystery.jpg; 6606; a28d342db2d2081f9d2eb287d49c1110\n" +
                "    wired-science.jpg; 16; 67bc4bf64a29239a9f148fb768bfbbc8\n" +
                "    wolf_slice_1.jpg; 6606; a28d342db2d2081f9d2eb287d49c1110\n" +
                "    index.html; 106102; 99248bc850c65b03b04776342e4b3e7d\n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertTrue(sb.getBlockSize() >= 1024);
        Assert.assertEquals(15728640, sb.getBlocksCount());
        Assert.assertEquals(13918208, sb.getFreeBlocksCount());
        Assert.assertTrue(sb.getINodesCount() > 0);
        Assert.assertTrue(sb.getFreeInodesCount() > 0);
        Assert.assertTrue(sb.getBlocksPerGroup() > 0);
        Assert.assertTrue(sb.getINodesPerGroup() > 0);
        Assert.assertEquals(Ext2Constants.EXT2_DYNAMIC_REV, sb.getRevLevel());
        Assert.assertEquals(Ext2Constants.EXT2_VALID_FS, sb.getState());
        Assert.assertEquals(0, sb.getINodeSize() % 2);

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);
        Assert.assertTrue(gds[0].getBlockBitmap() > 0);
        Assert.assertTrue(gds[0].getInodeBitmap() > 0);
        Assert.assertTrue(gds[0].getInodeTable() > 0);
        Assert.assertTrue(gds[0].getFreeBlocksCount() >= 0);
        Assert.assertTrue(gds[0].getFreeInodesCount() >= 0);
    }

    @Test
    public void testReadExt4Mmp() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-mmp.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        String expectedStructure =
            "type: EXT2 vol: total:2998272 free:1870848\n" +
                "  /; \n" +
                "    lost+found; \n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertEquals(2998272, sb.getBlocksCount());
        Assert.assertEquals(1870848, sb.getFreeBlocksCount());
        long incompatFeatures = sb.getFeatureIncompat();
        Assert.assertTrue((incompatFeatures & Ext2Constants.EXT4_FEATURE_INCOMPAT_MMP) != 0);

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);

        FSDirectory rootDirectory = fs.getRootEntry().getDirectory();
        Assert.assertNotNull(rootDirectory);
        FSEntry lostFound = rootDirectory.getEntry("lost+found");
        Assert.assertNotNull(lostFound);
        Assert.assertTrue(lostFound.isDirectory());
    }

    @Test
    public void testReadExt4LargeDirectory() throws Exception {

        // Filesystem created without the 'dir_index' feature
        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-large-directory.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        FSDirectory rootDirectory = fs.getRootEntry().getDirectory();
        FSDirectory largeDirectory = rootDirectory.getEntry("large-directory").getDirectory();

        int childCount = 0;
        Iterator<? extends FSEntry> iterator = largeDirectory.iterator();
        while (iterator.hasNext()) {
            FSEntry entry = iterator.next();

            if (entry.isFile()) {
                Assert.assertEquals("b1946ac92492d2347c6235b4d2611184", DataStructureAsserts.getMD5Digest(entry.getFile()));
                childCount++;
            }
        }

        Assert.assertEquals(65001, childCount);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertTrue(sb.getBlockSize() >= 1024);
        Assert.assertTrue(sb.getBlocksCount() > 65000);

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);
    }

    @Test
    public void testReadExt4LargeDirectoryWithIndex() throws Exception {

        // Filesystem created with the 'dir_index' feature
        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-large-dir-with-index.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        FSDirectory rootDirectory = fs.getRootEntry().getDirectory();
        FSDirectory largeDirectory = rootDirectory.getEntry("large-directory").getDirectory();

        int childCount = 0;
        Iterator<? extends FSEntry> iterator = largeDirectory.iterator();
        while (iterator.hasNext()) {
            FSEntry entry = iterator.next();

            if (entry.isFile()) {
                Assert.assertEquals("b1946ac92492d2347c6235b4d2611184", DataStructureAsserts.getMD5Digest(entry.getFile()));
                childCount++;
            }
        }

        Assert.assertEquals(65001, childCount);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        long incompatFeatures = sb.getFeatureIncompat();
        Assert.assertTrue((incompatFeatures & Ext2Constants.EXT2_FEATURE_INCOMPAT_FILETYPE) != 0);
        Assert.assertTrue(sb.getBlocksCount() > 65000);
        Assert.assertTrue(sb.getINodesCount() > 65001);

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);

        INode rootInode = fs.getINode(Ext2Constants.EXT2_ROOT_INO);
        Assert.assertNotNull(rootInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFDIR, rootInode.getMode() & Ext2Constants.EXT2_S_IFMT);
        Assert.assertTrue(rootInode.getSize() > 0);
        Assert.assertTrue(rootInode.getLinksCount() >= 2);
    }

    @Test
    public void testReadExt4FlexBG() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-flex-bg.img", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        String expectedStructure =
            "type: EXT2 vol:ext4-ftw-omgz total:127926272 free:123949056\n" +
            "  /; \n" +
            "    lost+found; \n" +
            "    wolf_slice_1.jpg; 6606; a28d342db2d2081f9d2eb287d49c1110\n" +
            "    wired-science.jpg; 16; 67bc4bf64a29239a9f148fb768bfbbc8\n" +
            "    The_Rabies_Virus_Remains_a_Medical_Mystery.jpg; 6606; a28d342db2d2081f9d2eb287d49c1110\n" +
            "    console; 0; d41d8cd98f00b204e9800998ecf8427e\n" +
            "    sda1; 0; d41d8cd98f00b204e9800998ecf8427e\n" +
            "    fifo; 0; d41d8cd98f00b204e9800998ecf8427e\n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertEquals("ext4-ftw-omgz", sb.getVolumeName());
        Assert.assertEquals(127926272, sb.getBlocksCount());
        Assert.assertEquals(123949056, sb.getFreeBlocksCount());
        Assert.assertTrue(sb.getBlockSize() >= 1024);
        long incompatFeatures = sb.getFeatureIncompat();
        Assert.assertTrue((incompatFeatures & Ext2Constants.EXT4_FEATURE_INCOMPAT_FLEX_BG) != 0);
        Assert.assertTrue(sb.isUsingFlexibleBlockGroups());
        Assert.assertTrue(sb.getBlocksPerFlex() > 0);

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);
        Assert.assertTrue(gds[0].getBlockBitmap() > 0);
        Assert.assertTrue(gds[0].getInodeBitmap() > 0);
        Assert.assertTrue(gds[0].getInodeTable() > 0);

        INode rootInode = fs.getINode(Ext2Constants.EXT2_ROOT_INO);
        Assert.assertNotNull(rootInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFDIR, rootInode.getMode() & Ext2Constants.EXT2_S_IFMT);
    }

    @Test
    public void testReadExt4MetaBG() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-meta-bg.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        String expectedStructure =
            "type: EXT2 vol: total:4997120 free:3680256\n" +
                "  /; \n" +
                "    lost+found; \n" +
                "    Fairy-Penguin.jpg; 60472; 78da81a8cf672de95d27214d44a5ea59\n" +
                "    why.jpg; 30965; 9b82ac413bb4204a4cf6d3e801af38fd\n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);

        Superblock sb = fs.getSuperblock();
        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertEquals(4997120, sb.getBlocksCount());
        Assert.assertEquals(3680256, sb.getFreeBlocksCount());
        long incompatFeatures = sb.getFeatureIncompat();
        Assert.assertTrue((incompatFeatures & Ext2Constants.EXT2_FEATURE_INCOMPAT_META_BG) != 0);
        Assert.assertTrue(sb.getBlockSize() >= 1024);
        Assert.assertEquals(Ext2Constants.EXT2_DYNAMIC_REV, sb.getRevLevel());

        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertTrue(gds.length > 0);

        INode rootInode = fs.getINode(Ext2Constants.EXT2_ROOT_INO);
        Assert.assertNotNull(rootInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFDIR, rootInode.getMode() & Ext2Constants.EXT2_S_IFMT);

        FSDirectory rootDirectory = fs.getRootEntry().getDirectory();
        FSEntry fairyPenguin = rootDirectory.getEntry("Fairy-Penguin.jpg");
        Assert.assertNotNull(fairyPenguin);
        Assert.assertTrue(fairyPenguin.isFile());
        Assert.assertEquals(60472, fairyPenguin.getFile().getLength());
        Assert.assertEquals("78da81a8cf672de95d27214d44a5ea59",
            DataStructureAsserts.getMD5Digest(fairyPenguin.getFile()));

        FSEntry why = rootDirectory.getEntry("why.jpg");
        Assert.assertNotNull(why);
        Assert.assertTrue(why.isFile());
        Assert.assertEquals(30965, why.getFile().getLength());
        Assert.assertEquals("9b82ac413bb4204a4cf6d3e801af38fd",
            DataStructureAsserts.getMD5Digest(why.getFile()));
    }

    @Test
    public void testReadExt4Superblock() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-flex-bg.img", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        Superblock sb = fs.getSuperblock();
        Assert.assertNotNull(sb);

        Assert.assertEquals(0xEF53, sb.getMagic());
        Assert.assertEquals(Ext2Constants.EXT2_DYNAMIC_REV, sb.getRevLevel());
        Assert.assertEquals(0, sb.getMinorRevLevel());
        Assert.assertEquals(Ext2Constants.EXT2_VALID_FS, sb.getState());
        Assert.assertEquals(Ext2Constants.EXT2_ERRORS_CONTINUE, sb.getErrors());

        Assert.assertTrue(sb.getBlockSize() >= 1024);
        Assert.assertTrue(sb.getBlockSize() <= 65536);
        Assert.assertTrue(sb.getINodeSize() >= 128);
        Assert.assertTrue(sb.getINodeSize() <= 1024);
        Assert.assertEquals(0, sb.getINodeSize() % 2);

        Assert.assertTrue(sb.getBlocksCount() > 0);
        Assert.assertTrue(sb.getFreeBlocksCount() <= sb.getBlocksCount());
        Assert.assertTrue(sb.getRBlocksCount() <= sb.getBlocksCount());
        Assert.assertTrue(sb.getINodesCount() > 0);
        Assert.assertTrue(sb.getFreeInodesCount() <= sb.getINodesCount());

        Assert.assertTrue(sb.getBlocksPerGroup() > 0);
        Assert.assertTrue(sb.getINodesPerGroup() > 0);
        Assert.assertTrue(sb.getFirstDataBlock() >= 0);
        Assert.assertTrue(sb.getFirstDataBlock() <= 1);

        Assert.assertTrue(sb.getMntCount() >= 0);
        Assert.assertTrue(sb.getMaxMntCount() > 0);

        Assert.assertTrue(sb.getFirstInode() >= 1);

        byte[] uuid = sb.getUUID();
        Assert.assertNotNull(uuid);
        Assert.assertEquals(16, uuid.length);

        long featureCompat = sb.getFeatureCompat();
        long featureIncompat = sb.getFeatureIncompat();
        long featureROCompat = sb.getFeatureROCompat();
        Assert.assertTrue(featureCompat >= 0);
        Assert.assertTrue(featureIncompat >= 0);
        Assert.assertTrue(featureROCompat >= 0);

        long groupCount = Ext2Utils.ceilDiv(sb.getBlocksCount(), sb.getBlocksPerGroup());
        Assert.assertTrue(groupCount > 0);
        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertEquals((int) groupCount, gds.length);
    }

    @Test
    public void testReadExt4GroupDescriptors() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-meta-bg.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        Superblock sb = fs.getSuperblock();
        GroupDescriptor[] gds = fs.getGroupDescriptors();
        Assert.assertNotNull(gds);
        Assert.assertTrue(gds.length > 0);

        long groupCount = Ext2Utils.ceilDiv(sb.getBlocksCount(), sb.getBlocksPerGroup());
        Assert.assertEquals((int) groupCount, gds.length);

        for (int i = 0; i < gds.length; i++) {
            GroupDescriptor gd = gds[i];
            Assert.assertNotNull("Group descriptor " + i + " should not be null", gd);
            Assert.assertEquals(32, gd.size());
            Assert.assertTrue("Block bitmap in group " + i + " should be > 0",
                gd.getBlockBitmap() > 0);
            Assert.assertTrue("Inode bitmap in group " + i + " should be > 0",
                gd.getInodeBitmap() > 0);
            Assert.assertTrue("Inode table in group " + i + " should be > 0",
                gd.getInodeTable() > 0);
            Assert.assertTrue("Block bitmap < Inode bitmap in group " + i,
                gd.getBlockBitmap() < gd.getInodeBitmap());
            Assert.assertTrue("Inode bitmap < Inode table in group " + i,
                gd.getInodeBitmap() < gd.getInodeTable());
            Assert.assertTrue("Free inodes count in group " + i + " should be >= 0",
                gd.getFreeInodesCount() >= 0);
            Assert.assertTrue("Free blocks count in group " + i + " should be >= 0",
                gd.getFreeBlocksCount() >= 0);
        }
    }

    @Test
    public void testReadExt4InodeTable() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-meta-bg.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        Superblock sb = fs.getSuperblock();
        INode rootInode = fs.getINode(Ext2Constants.EXT2_ROOT_INO);
        Assert.assertNotNull(rootInode);
        Assert.assertEquals(Ext2Constants.EXT2_ROOT_INO, rootInode.getINodeNr());
        Assert.assertEquals(Ext2Constants.EXT2_S_IFDIR, rootInode.getMode() & Ext2Constants.EXT2_S_IFMT);
        Assert.assertTrue("Root inode size should be > 0", rootInode.getSize() > 0);
        Assert.assertTrue("Root inode links count should be >= 2",
            rootInode.getLinksCount() >= 2);
        Assert.assertEquals(0, rootInode.getUid());
        Assert.assertEquals(0, rootInode.getGid());
        Assert.assertTrue("Root inode atime should be > 0", rootInode.getAtime() > 0);
        Assert.assertTrue("Root inode ctime should be > 0", rootInode.getCtime() > 0);
        Assert.assertTrue("Root inode mtime should be > 0", rootInode.getMtime() > 0);
        Assert.assertEquals(0, rootInode.getDtime());

        int inodeSize = sb.getINodeSize();
        Assert.assertTrue("Inode size should be >= 128", inodeSize >= 128);
        Assert.assertTrue("Inode size should be <= 1024", inodeSize <= 1024);

        byte[] rootBlockData = rootInode.getINodeBlockData();
        Assert.assertNotNull(rootBlockData);
        Assert.assertEquals(64, rootBlockData.length);

        INode lostFoundInode = null;
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        lostFoundInode = ((Ext2Entry) rootDir.getEntry("lost+found")).getINode();
        Assert.assertNotNull(lostFoundInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFDIR,
            lostFoundInode.getMode() & Ext2Constants.EXT2_S_IFMT);
        Assert.assertTrue("lost+found inode size should be > 0",
            lostFoundInode.getSize() > 0);

        long journalINum = sb.getJournalINum();
        Assert.assertTrue("Journal inode number should be >= 8", journalINum >= 8);
        INode journalInode = fs.getINode(journalINum);
        Assert.assertNotNull(journalInode);
    }

    @Test
    public void testReadExt4ExtentTree() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-meta-bg.dd", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        INode fairyPenguinInode = null;
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        fairyPenguinInode = ((Ext2Entry) rootDir.getEntry("Fairy-Penguin.jpg")).getINode();
        Assert.assertNotNull(fairyPenguinInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFREG,
            fairyPenguinInode.getMode() & Ext2Constants.EXT2_S_IFMT);

        long flags = fairyPenguinInode.getFlags();
        Assert.assertTrue("Inode should have extents flag",
            (flags & Ext2Constants.EXT4_INODE_EXTENTS_FLAG) != 0);

        byte[] blockData = fairyPenguinInode.getINodeBlockData();
        Assert.assertNotNull(blockData);
        Assert.assertEquals(64, blockData.length);

        ExtentHeader extentHeader = new ExtentHeader(blockData);
        Assert.assertNotNull(extentHeader);
        Assert.assertEquals(ExtentHeader.MAGIC, extentHeader.getMagic());
        Assert.assertTrue("Entry count should be > 0", extentHeader.getEntryCount() > 0);
        Assert.assertTrue("Max entry count should be >= entry count",
            extentHeader.getMaximumEntryCount() >= extentHeader.getEntryCount());

        int depth = extentHeader.getDepth();
        Assert.assertTrue("Depth should be >= 0", depth >= 0);

        if (depth == 0) {
            Extent[] extents = extentHeader.getExtentEntries();
            Assert.assertNotNull(extents);
            Assert.assertEquals(extentHeader.getEntryCount(), extents.length);

            long totalLogicalBlocks = 0;
            for (int i = 0; i < extents.length; i++) {
                Extent ext = extents[i];
                Assert.assertNotNull("Extent " + i + " should not be null", ext);
                Assert.assertTrue("Block index " + i + " should be >= 0",
                    ext.getBlockIndex() >= 0);
                Assert.assertTrue("Block count " + i + " should be > 0",
                    ext.getBlockCount() > 0);
                Assert.assertTrue("Start low " + i + " should be > 0",
                    ext.getStartLow() > 0);
                Assert.assertEquals(0, ext.getStartHigh());
                Assert.assertEquals("Block index " + i + " should match total",
                    totalLogicalBlocks, ext.getBlockIndex());
                totalLogicalBlocks += ext.getBlockCount();
            }
            Assert.assertTrue("Total logical blocks should match file size",
                totalLogicalBlocks * fs.getBlockSize() >= fairyPenguinInode.getSize());
        } else {
            Assert.assertTrue("Non-zero depth means index entries exist",
                extentHeader.getIndexEntries() != null);
        }

        long resolvedBlock = fairyPenguinInode.getDataBlockNr(0);
        Assert.assertTrue("First data block should be > 0", resolvedBlock > 0);

        Assert.assertEquals("60472", Long.toString(fairyPenguinInode.getSize()));
    }

    @Test
    public void testReadExt4DirectoryEntries() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-flex-bg.img", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        FSDirectory rootDirectory = fs.getRootEntry().getDirectory();
        Assert.assertNotNull(rootDirectory);

        int fileCount = 0;
        int dirCount = 0;
        Iterator<? extends FSEntry> iterator = rootDirectory.iterator();
        while (iterator.hasNext()) {
            FSEntry entry = iterator.next();
            Assert.assertNotNull("Entry name should not be null", entry.getName());
            Assert.assertTrue("Entry name should not be empty", entry.getName().length() > 0);

            if (entry instanceof Ext2Entry) {
                Ext2Entry ext2Entry = (Ext2Entry) entry;
                Assert.assertNotNull("INode should not be null for " + entry.getName(),
                    ext2Entry.getINode());
                Assert.assertTrue("Inode number should be > 0 for " + entry.getName(),
                    ext2Entry.getINode().getINodeNr() > 0);
                Assert.assertTrue("Type should be >= 0 for " + entry.getName(),
                    ext2Entry.getType() >= 0);
                Assert.assertTrue("Type should be <= 8 for " + entry.getName(),
                    ext2Entry.getType() <= Ext2Constants.EXT2_FT_MAX);
            }

            if (entry.isFile()) {
                fileCount++;
                FSFile file = entry.getFile();
                Assert.assertNotNull("File should not be null for " + entry.getName(), file);
                Assert.assertTrue("File length should be >= 0 for " + entry.getName(),
                    file.getLength() >= 0);
            } else if (entry.isDirectory()) {
                dirCount++;
            }
        }

        Assert.assertTrue("Should have at least 6 files", fileCount >= 6);
        Assert.assertTrue("Should have at least 1 directory (lost+found)", dirCount >= 1);

        FSEntry lostFound = rootDirectory.getEntry("lost+found");
        Assert.assertNotNull("lost+found should exist", lostFound);
        Assert.assertTrue("lost+found should be a directory", lostFound.isDirectory());

        FSEntry console = rootDirectory.getEntry("console");
        Assert.assertNotNull("console should exist", console);
        Assert.assertTrue("console should be a file", console.isFile());
        Assert.assertEquals(0, console.getFile().getLength());
        Assert.assertEquals("d41d8cd98f00b204e9800998ecf8427e",
            DataStructureAsserts.getMD5Digest(console.getFile()));

        FSEntry wiredScience = rootDirectory.getEntry("wired-science.jpg");
        Assert.assertNotNull("wired-science.jpg should exist", wiredScience);
        Assert.assertTrue("wired-science.jpg should be a file", wiredScience.isFile());
        Assert.assertEquals(16, wiredScience.getFile().getLength());
        Assert.assertEquals("67bc4bf64a29239a9f148fb768bfbbc8",
            DataStructureAsserts.getMD5Digest(wiredScience.getFile()));
    }

    @Test
    public void testReadExt4JournalFields() throws Exception {

        device = new FileDevice(
            FileSystemTestUtils.getTestFileWithCleanup(
                "test/fs/ext4/ext4-flex-bg.img", testFiles), "r");
        Ext2FileSystemType type = fss.getFileSystemType(Ext2FileSystemType.ID);
        Ext2FileSystem fs = type.create(device, true);

        Superblock sb = fs.getSuperblock();

        byte[] journalUUID = sb.getJournalUUID();
        Assert.assertNotNull("Journal UUID should not be null", journalUUID);
        Assert.assertEquals(16, journalUUID.length);

        long journalINum = sb.getJournalINum();
        Assert.assertTrue("Journal inode number should be >= 8", journalINum >= 8);

        long journalDev = sb.getJournalDev();
        Assert.assertTrue("Journal device should be >= 0", journalDev >= 0);

        INode journalInode = fs.getINode(journalINum);
        Assert.assertNotNull("Journal inode should be readable", journalInode);
        Assert.assertEquals(Ext2Constants.EXT2_S_IFREG,
            journalInode.getMode() & Ext2Constants.EXT2_S_IFMT);
        Assert.assertTrue("Journal inode size should be > 0",
            journalInode.getSize() > 0);
        Assert.assertTrue("Journal inode links count should be >= 1",
            journalInode.getLinksCount() >= 1);

        byte[] journalBlockData = journalInode.getINodeBlockData();
        Assert.assertNotNull(journalBlockData);
        Assert.assertEquals(64, journalBlockData.length);

        long lastOrphan = sb.getLastOrphan();
        Assert.assertTrue("Last orphan should be >= 0", lastOrphan >= 0);
    }

    @AfterClass
    public static void cleanup() {
        FileSystemTestUtils.cleanupTestFiles(testFiles);
    }
}
