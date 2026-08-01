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
 
package org.jnode.test.fs.jfat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.DataStructureAsserts;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatFileSystemTest {

    private FileDevice device;
    private FileSystemService fss;
    private File tempFile;

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
    }

    @After
    public void tearDown() throws Exception {
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void testReadFat32Disk() throws Exception {
        device = new FileDevice(FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32"), "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(device, true);

        BootSector bs = fs.getBootSector();
        assertTrue("Should be FAT32", bs.isFat32());
        assertFalse("Should not be FAT16", bs.isFat16());
        assertFalse("Should not be FAT12", bs.isFat12());

        assertEquals("Bytes per sector should be 512", 512, bs.getBytesPerSector());
        assertTrue("Sectors per cluster should be > 0", bs.getSectorsPerCluster() > 0);
        assertTrue("Number of FATs should be > 0", bs.getNrFats() > 0);
        assertTrue("Cluster size should be > 0", fs.getClusterSize() > 0);

        long totalSpace = fs.getTotalSpace();
        long freeSpace = fs.getFreeSpace();
        assertTrue("Total space should be > 0", totalSpace > 0);
        assertTrue("Free space should be >= 0", freeSpace >= 0);
        assertTrue("Free space should be <= total space", freeSpace <= totalSpace);

        FSDirectory root = fs.getRootEntry().getDirectory();
        int rootCount = countEntries(root);
        assertTrue("Root should have at least 3 entries", rootCount >= 3);

        FSEntry dir1 = root.getEntry("dir1");
        assertNotNull("dir1 should exist", dir1);
        assertTrue("dir1 should be a directory", dir1.isDirectory());

        FSEntry dir2 = root.getEntry("dir2");
        assertNotNull("dir2 should exist", dir2);
        assertTrue("dir2 should be a directory", dir2.isDirectory());

        FSEntry testFile = root.getEntry("test.txt");
        assertNotNull("test.txt should exist", testFile);
        assertTrue("test.txt should be a file", testFile.isFile());
        assertEquals("test.txt should be 18 bytes", 18, testFile.getFile().getLength());

        FSFile file = testFile.getFile();
        ByteBuffer buf = ByteBuffer.allocate(18);
        file.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertEquals("test.txt content length should be 18", 18, data.length);

        String expectedStructure =
            "type: JFAT vol: total:" + totalSpace + " free:" + freeSpace + "\n" +
                "  ; \n" +
                "    dir1; \n" +
                "      test.txt; 18; 80aeb09eb86de4c4a7d1f877451dc2a2\n" +
                "    dir2; \n" +
                "      test.txt; 18; 1b20f937ce4a3e9241cc907086169ad7\n" +
                "    test.txt; 18; fd99fcfc86ba71118bd64c2d9f4b54a4\n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);
    }

    @Test
    public void testReadFat16Disk() throws Exception {
        device = new FileDevice(FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat16"), "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(device, true);

        BootSector bs = fs.getBootSector();
        assertTrue("Should be FAT16", bs.isFat16());
        assertFalse("Should not be FAT32", bs.isFat32());
        assertFalse("Should not be FAT12", bs.isFat12());

        assertEquals("Bytes per sector should be 512", 512, bs.getBytesPerSector());
        assertTrue("Sectors per cluster should be > 0", bs.getSectorsPerCluster() > 0);
        assertTrue("Number of FATs should be > 0", bs.getNrFats() > 0);
        assertTrue("Cluster size should be > 0", fs.getClusterSize() > 0);

        long totalSpace = fs.getTotalSpace();
        long freeSpace = fs.getFreeSpace();
        assertTrue("Total space should be > 0", totalSpace > 0);
        assertTrue("Free space should be >= 0", freeSpace >= 0);
        assertTrue("Free space should be <= total space", freeSpace <= totalSpace);

        FSDirectory root = fs.getRootEntry().getDirectory();

        FSEntry testFile = root.getEntry("test.txt");
        assertNotNull("test.txt should exist", testFile);
        assertTrue("test.txt should be a file", testFile.isFile());
        assertEquals("test.txt should be 18 bytes", 18, testFile.getFile().getLength());

        FSFile file = testFile.getFile();
        ByteBuffer buf = ByteBuffer.allocate(18);
        file.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertEquals("test.txt content length should be 18", 18, data.length);

        String expectedStructure =
            "type: JFAT vol: total:" + totalSpace + " free:" + freeSpace + "\n" +
                "  ; \n" +
                "    test.txt; 18; ff035bff2dcf972ee7dfd023455997ef\n";

        DataStructureAsserts.assertStructure(fs, expectedStructure);
    }

    @Test
    public void testReadFat12Disk() throws Exception {
        tempFile = File.createTempFile("jfat12-test", ".img");
        tempFile.deleteOnExit();

        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        fileDevice.setLength(360L * 512);

        BootSector bs = new BootSector(512);
        bs.setBS_JmpBoot(new byte[]{(byte) 0xEB, (byte) 0x3C, (byte) 0x90});
        bs.setBS_OemName("JNODE10");
        bs.setBPB_BytesPerSector(512);
        bs.setBPB_SecPerCluster(2);
        bs.setBPB_RsvSecCount(1);
        bs.setBPB_NoFATs(2);
        bs.setBPB_RootEntCnt(224);
        bs.setBPB_TotSec16(720);
        bs.setBPB_MediumDescriptor(0xF0);
        bs.setBPB_FATSz16(2);
        bs.setBPB_SecPerTrk(9);
        bs.setBPB_NumHeads(2);
        bs.setBPB_HiddSec(0);
        bs.setBPB_TotSec32(0);
        bs.setBS_DrvNum(0x00);
        bs.setBS_Reserved1(0);
        bs.setBS_BootSig(0x29);
        bs.setBS_VolID(0x12345678);
        bs.setBS_VolLab("TEST12     ");
        bs.setBS_FilSysType("FAT12   ");

        bs.write(fileDevice.getAPI(BlockDeviceAPI.class), 0);

        BlockDeviceAPI api = fileDevice.getAPI(BlockDeviceAPI.class);
        byte[] fatSector = new byte[512];
        fatSector[0] = (byte) 0xF0;
        fatSector[1] = (byte) 0xFF;
        fatSector[2] = (byte) 0xFF;
        api.write(1 * 512, ByteBuffer.wrap(fatSector));
        api.write((1 + 2) * 512, ByteBuffer.wrap(fatSector));

        api.flush();
        fileDevice.close();

        FileDevice readOnlyDevice = new FileDevice(tempFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(readOnlyDevice, true);

        BootSector readBs = fs.getBootSector();
        assertTrue("Should be FAT12", readBs.isFat12());
        assertFalse("Should not be FAT16", readBs.isFat16());
        assertFalse("Should not be FAT32", readBs.isFat32());

        assertEquals("Bytes per sector should be 512", 512, readBs.getBytesPerSector());
        assertEquals("Sectors per cluster should be 2", 2, readBs.getSectorsPerCluster());
        assertEquals("Number of FATs should be 2", 2, readBs.getNrFats());
        assertEquals("Root dir entries should be 224", 224, readBs.getNrRootDirEntries());
        assertEquals("FAT size should be 2 sectors", 2, readBs.getSectorsPerFat());

        long totalSpace = fs.getTotalSpace();
        long freeSpace = fs.getFreeSpace();
        assertTrue("Total space should be > 0", totalSpace > 0);
        assertTrue("Free space should be > 0", freeSpace > 0);
        assertTrue("Free space should be <= total space", freeSpace <= totalSpace);

        FSDirectory root = fs.getRootEntry().getDirectory();
        assertNotNull("Root directory should not be null", root);

        fs.close();
        readOnlyDevice.close();
    }

    @Test
    public void testLongFileNames() throws Exception {
        tempFile = File.createTempFile("jfat-lfn-test", ".img");
        tempFile.deleteOnExit();

        FileDevice fileDevice = new FileDevice(tempFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);

        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        String longName1 = "This is a very long filename that exceeds 8.3 format.txt";
        String longName2 = "\u6587\u4ef6\u540d\u6d4b\u8bd5.txt";
        String longName3 = "Another_long_name_with_numbers_1234567890.log";

        FSFile file1 = rootDir.addFile(longName1).getFile();
        file1.write(0, ByteBuffer.wrap("Content1".getBytes()));
        file1.flush();

        FSFile file2 = rootDir.addFile(longName2).getFile();
        file2.write(0, ByteBuffer.wrap("Content2".getBytes()));
        file2.flush();

        FSFile file3 = rootDir.addFile(longName3).getFile();
        file3.write(0, ByteBuffer.wrap("Content3".getBytes()));
        file3.flush();

        fs.flush();
        fs.close();

        fileDevice.close();
        fileDevice = new FileDevice(tempFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        fs = type.create(fileDevice, true);

        rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry1 = rootDir.getEntry(longName1);
        assertNotNull("Long filename 1 should exist", entry1);
        assertTrue("Entry should be a file", entry1.isFile());
        assertEquals("Name should be preserved", longName1, entry1.getName());

        FSEntry entry2 = rootDir.getEntry(longName2);
        assertNotNull("Long filename 2 should exist", entry2);
        assertTrue("Entry should be a file", entry2.isFile());
        assertEquals("Name should be preserved", longName2, entry2.getName());

        FSEntry entry3 = rootDir.getEntry(longName3);
        assertNotNull("Long filename 3 should exist", entry3);
        assertTrue("Entry should be a file", entry3.isFile());
        assertEquals("Name should be preserved", longName3, entry3.getName());

        FSFile readFile1 = entry1.getFile();
        ByteBuffer buf1 = ByteBuffer.allocate(8);
        readFile1.read(0, buf1);
        buf1.flip();
        byte[] data1 = new byte[buf1.remaining()];
        buf1.get(data1);
        assertEquals("Content1 mismatch", "Content1", new String(data1));

        FSFile readFile2 = entry2.getFile();
        ByteBuffer buf2 = ByteBuffer.allocate(8);
        readFile2.read(0, buf2);
        buf2.flip();
        byte[] data2 = new byte[buf2.remaining()];
        buf2.get(data2);
        assertEquals("Content2 mismatch", "Content2", new String(data2));

        FSFile readFile3 = entry3.getFile();
        ByteBuffer buf3 = ByteBuffer.allocate(8);
        readFile3.read(0, buf3);
        buf3.flip();
        byte[] data3 = new byte[buf3.remaining()];
        buf3.get(data3);
        assertEquals("Content3 mismatch", "Content3", new String(data3));

        long totalSpace = fs.getTotalSpace();
        long freeSpace = fs.getFreeSpace();
        assertTrue("Total space should be > 0", totalSpace > 0);
        assertTrue("Free space should be > 0", freeSpace > 0);
        assertTrue("Free space should be <= total space", freeSpace <= totalSpace);

        fs.close();
        fileDevice.close();
    }

    private int countEntries(FSDirectory dir) throws Exception {
        int count = 0;
        Iterator<? extends FSEntry> it = dir.iterator();
        while (it.hasNext()) {
            FSEntry e = it.next();
            if (!".".equals(e.getName()) && !"..".equals(e.getName())) {
                count++;
            }
        }
        return count;
    }
}
