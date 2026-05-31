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

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatWriteTest {

    private static final String FILE_NAME = "hello.txt";
    private static final byte[] TEST_DATA = "Hello JFAT!".getBytes();
    private static final String DIR_NAME = "subdir";

    private File diskFile;
    private FileDevice fileDevice;
    private FileSystemService fss;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }

        DummyPluginDescriptor desc = new DummyPluginDescriptor(true);
        DummyExtensionPoint ep = new DummyExtensionPoint("types", "org.jnode.fs.types", "types");
        desc.addExtensionPoint(ep);
        for (String className : new String[]{
            "org.jnode.fs.jfat.FatFileSystemType",
            "org.jnode.fs.ext2.Ext2FileSystemType",
            "org.jnode.fs.fat.FatFileSystemType",
            "org.jnode.fs.exfat.ExFatFileSystemType",
            "org.jnode.fs.iso9660.ISO9660FileSystemType",
            "org.jnode.fs.hfsplus.HfsPlusFileSystemType",
            "org.jnode.fs.ntfs.NTFSFileSystemType",
            "org.jnode.fs.jifs.JifsFileSystemType"
        }) {
            DummyExtension extension = new DummyExtension();
            DummyConfigurationElement element = new DummyConfigurationElement();
            element.addAttribute("class", className);
            extension.addElement(element);
            ep.addExtension(extension);
        }
        fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-write-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    /**
     * Tests writing a file larger than cluster size to verify FAT chaining.
     */
    @Test
    public void testLargeFile() throws Exception {
        final int CLUSTER_SIZE = 1024; // 1K clusters
        final int FILE_SIZE = CLUSTER_SIZE * 3; // 3 clusters

        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile("large.bin").getFile();

        // Write data larger than one cluster
        ByteBuffer buffer = ByteBuffer.allocate(FILE_SIZE);
        for (int i = 0; i < FILE_SIZE; i++) {
            buffer.put((byte) (i & 0xFF));
        }
        buffer.flip();
        file.write(0, buffer);
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        // Read back and verify
        FSEntry entry = rootDir.getEntry("large.bin");
        assertNotNull("File should exist", entry);
        assertTrue("Should be a file", entry.isFile());

        FSFile readFile = entry.getFile();
        assertEquals("Wrong file length", FILE_SIZE, readFile.getLength());

        ByteBuffer readBuffer = ByteBuffer.allocate(FILE_SIZE);
        readFile.read(0, readBuffer);
        assertTrue("Data mismatch", Arrays.equals(buffer.array(), readBuffer.array()));

        fs.close();
    }

    /**
     * Tests creating and deleting files.
     */
    @Test
    public void testFileDeletion() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create a file
        FSFile file = rootDir.addFile("delete_me.txt").getFile();
        file.write(0, ByteBuffer.wrap("test data".getBytes()));
        file.flush();
        fs.flush();

        // Verify it exists
        FSEntry entry = rootDir.getEntry("delete_me.txt");
        assertNotNull("File should exist after creation", entry);
        assertTrue("Should be a file", entry.isFile());

        // Delete the file
        rootDir.remove("delete_me.txt");
        fs.flush();

        // Verify it's gone
        entry = rootDir.getEntry("delete_me.txt");
        assertNull("File should not exist after deletion", entry);

        fs.close();
    }

    /**
     * Tests creating deeply nested directory structures.
     */
    @Test
    public void testNestedDirectories() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create a deeply nested path: a/b/c/d/e
        FSDirectory dirA = rootDir.addDirectory("a").getDirectory();
        FSDirectory dirB = dirA.addDirectory("b").getDirectory();
        FSDirectory dirC = dirB.addDirectory("c").getDirectory();
        FSDirectory dirD = dirC.addDirectory("d").getDirectory();
        FSDirectory dirE = dirD.addDirectory("e").getDirectory();

        // Create a file in the deepest directory
        FSFile file = dirE.addFile("nested.txt").getFile();
        file.write(0, ByteBuffer.wrap("nested data".getBytes()));
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();

        // Verify the path exists
        rootDir = fs.getRootEntry().getDirectory();
        FSEntry entryA = rootDir.getEntry("a");
        assertNotNull("Directory 'a' should exist", entryA);
        assertTrue("Entry 'a' should be a directory", entryA.isDirectory());

        FSDirectory dirA2 = entryA.getDirectory();
        FSEntry entryB = dirA2.getEntry("b");
        assertNotNull("Directory 'a/b' should exist", entryB);
        assertTrue("Entry 'a/b' should be a directory", entryB.isDirectory());

        FSDirectory dirB2 = entryB.getDirectory();
        FSEntry entryC = dirB2.getEntry("c");
        assertNotNull("Directory 'a/b/c' should exist", entryC);
        assertTrue("Entry 'a/b/c' should be a directory", entryC.isDirectory());

        FSDirectory dirC2 = entryC.getDirectory();
        FSEntry entryD = dirC2.getEntry("d");
        assertNotNull("Directory 'a/b/c/d' should exist", entryD);
        assertTrue("Entry 'a/b/c/d' should be a directory", entryD.isDirectory());

        FSDirectory dirD2 = entryD.getDirectory();
        FSEntry entryE = dirD2.getEntry("e");
        assertNotNull("Directory 'a/b/c/d/e' should exist", entryE);
        assertTrue("Entry 'a/b/c/d/e' should be a directory", entryE.isDirectory());

        FSDirectory dirE2 = entryE.getDirectory();
        FSEntry fileEntry = dirE2.getEntry("nested.txt");
        assertNotNull("File 'a/b/c/d/e/nested.txt' should exist", fileEntry);
        assertTrue("Entry should be a file", fileEntry.isFile());

        FSFile readFile = fileEntry.getFile();
        assertEquals("Wrong file length", 11, readFile.getLength());

        ByteBuffer readBuffer = ByteBuffer.allocate(11);
        readFile.read(0, readBuffer);
        assertTrue("Data mismatch", Arrays.equals("nested data".getBytes(), readBuffer.array()));

        fs.close();
    }

    /**
     * Tests extending and truncating files.
     */
    @Test
    public void testFileTruncation() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile("truncate_me.txt").getFile();

        // Write initial data
        byte[] initialData = "This is the initial content".getBytes();
        file.write(0, ByteBuffer.wrap(initialData));
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        // Verify initial content
        FSEntry entry = rootDir.getEntry("truncate_me.txt");
        assertNotNull("File should exist", entry);
        FSFile readFile = entry.getFile();
        assertEquals("Wrong initial length", initialData.length, readFile.getLength());

        ByteBuffer buffer = ByteBuffer.allocate(initialData.length);
        readFile.read(0, buffer);
        assertTrue("Initial data mismatch", Arrays.equals(initialData, buffer.array()));

        fs.close();

        // Reopen for writing and truncate
        fs = formatAndOpenRW(); // This will create a fresh filesystem
        rootDir = fs.getRootEntry().getDirectory();
        file = rootDir.addFile("truncate_me.txt").getFile();

        // Write shorter data
        byte[] shorterData = "Short".getBytes();
        file.write(0, ByteBuffer.wrap(shorterData));
        file.setLength(shorterData.length); // Truncate to shorter length
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        // Verify truncated content
        entry = rootDir.getEntry("truncate_me.txt");
        assertNotNull("File should exist after truncation", entry);
        readFile = entry.getFile();
        assertEquals("Wrong length after truncation", shorterData.length, readFile.getLength());

        buffer = ByteBuffer.allocate(shorterData.length);
        readFile.read(0, buffer);
        assertTrue("Truncated data mismatch", Arrays.equals(shorterData, buffer.array()));

        fs.close();
    }


    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }

    private FatFileSystem openReadOnly() throws Exception {
        fileDevice.close();
        fileDevice = new FileDevice(diskFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, true);
    }

    @Test
    public void testWriteAndReadBack() throws Exception {
        FatFileSystem fs = formatAndOpenRW();

        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(TEST_DATA));
        file.flush();
        fs.flush();

        FSEntry entryBefore = rootDir.getEntry(FILE_NAME);
        assertNotNull("File should exist in-memory before close", entryBefore);

        fs.close();
        fs = openReadOnly();

        rootDir = fs.getRootEntry().getDirectory();
        FSEntry entry = rootDir.getEntry(FILE_NAME);
        assertNotNull("File not found after remount", entry);
        assertTrue("Entry is not a file", entry.isFile());

        FSFile readFile = entry.getFile();
        assertEquals("Wrong file length", TEST_DATA.length, readFile.getLength());

        ByteBuffer readBuf = ByteBuffer.allocate(TEST_DATA.length);
        readFile.read(0, readBuf);
        assertTrue("Data mismatch", Arrays.equals(TEST_DATA, readBuf.array()));

        fs.close();
    }

    @Test
    public void testCreateDirectory() throws Exception {
        FatFileSystem fs = formatAndOpenRW();

        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSDirectory subDir = rootDir.addDirectory(DIR_NAME).getDirectory();
        assertNotNull("Directory not created", subDir);

        FSFile file = subDir.addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(TEST_DATA));
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();

        rootDir = fs.getRootEntry().getDirectory();
        FSEntry dirEntry = rootDir.getEntry(DIR_NAME);
        assertNotNull("Directory not found after remount", dirEntry);
        assertTrue("Entry is not a directory", dirEntry.isDirectory());

        FSDirectory readDir = dirEntry.getDirectory();
        FSEntry fileEntry = readDir.getEntry(FILE_NAME);
        assertNotNull("File not found in subdirectory", fileEntry);

        FSFile readFile = fileEntry.getFile();
        ByteBuffer readBuf = ByteBuffer.allocate(TEST_DATA.length);
        readFile.read(0, readBuf);
        assertTrue("Data mismatch in subdirectory", Arrays.equals(TEST_DATA, readBuf.array()));

        fs.close();
    }

    @Test
    public void testOverwriteFile() throws Exception {
        FatFileSystem fs = formatAndOpenRW();

        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile(FILE_NAME).getFile();
        byte[] firstData = "First version".getBytes();
        file.write(0, ByteBuffer.wrap(firstData));
        file.flush();
        fs.flush();

        file.setLength(0);
        byte[] secondData = "Second version!".getBytes();
        file.write(0, ByteBuffer.wrap(secondData));
        file.flush();
        fs.flush();

        fs.close();
        fs = openReadOnly();

        rootDir = fs.getRootEntry().getDirectory();
        FSFile readFile = rootDir.getEntry(FILE_NAME).getFile();
        assertEquals("Wrong length after overwrite", secondData.length, readFile.getLength());

        ByteBuffer readBuf = ByteBuffer.allocate(secondData.length);
        readFile.read(0, readBuf);
        assertTrue("Overwritten data mismatch", Arrays.equals(secondData, readBuf.array()));

        fs.close();
    }

    @Test
    public void testMultipleFiles() throws Exception {
        FatFileSystem fs = formatAndOpenRW();

        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        int fileCount = 5;
        byte[][] allData = new byte[fileCount][];

        for (int i = 0; i < fileCount; i++) {
            String name = "file" + i + ".txt";
            allData[i] = ("Content of file " + i).getBytes();
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(allData[i]));
            file.flush();
        }
        fs.flush();

        fs.close();
        fs = openReadOnly();

        rootDir = fs.getRootEntry().getDirectory();
        for (int i = 0; i < fileCount; i++) {
            String name = "file" + i + ".txt";
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("File " + name + " not found", entry);

            FSFile readFile = entry.getFile();
            ByteBuffer readBuf = ByteBuffer.allocate(allData[i].length);
            readFile.read(0, readBuf);
            assertTrue("Data mismatch for " + name, Arrays.equals(allData[i], readBuf.array()));
        }

        fs.close();
    }
}
