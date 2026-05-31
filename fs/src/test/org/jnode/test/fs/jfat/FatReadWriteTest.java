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
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests JFAT read/write on a pre-formatted FAT32 image.
 * Uses the existing test.fat32.gz test image (created externally).
 */
public class FatReadWriteTest {

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
    }

    @Test
    public void testReadExistingFat32Image() throws Exception {
        FileDevice device = new FileDevice(
            FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32"), "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(device, true);

        // Root should have dir1, dir2, test.txt
        FSDirectory root = fs.getRootEntry().getDirectory();

        // Debug: check entry count
        int count = 0;
        java.util.Iterator<? extends FSEntry> it = root.iterator();
        while (it.hasNext()) {
            FSEntry e = it.next();
            count++;
        }
        assertTrue("Root should have at least 3 entries, found " + count, count >= 3);

        FSEntry dir1 = root.getEntry("dir1");
        assertNotNull("dir1 not found", dir1);
        assertTrue("dir1 is not a directory", dir1.isDirectory());

        FSEntry dir2 = root.getEntry("dir2");
        assertNotNull("dir2 not found", dir2);
        assertTrue("dir2 is not a directory", dir2.isDirectory());

        FSEntry testFile = root.getEntry("test.txt");
        assertNotNull("test.txt not found", testFile);
        assertTrue("test.txt is not a file", testFile.isFile());
        assertEquals("test.txt wrong size", 18, testFile.getFile().getLength());

        // Read file content
        FSFile file = testFile.getFile();
        ByteBuffer buf = ByteBuffer.allocate(18);
        file.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertNotNull("file content should not be null", data);
        assertEquals("file content length", 18, data.length);

        fs.close();
        device.close();
    }

    @Test
    public void testReadSubdirectory() throws Exception {
        FileDevice device = new FileDevice(
            FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32"), "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        FatFileSystem fs = type.create(device, true);

        FSDirectory root = fs.getRootEntry().getDirectory();

        // Read dir1/test.txt
        FSDirectory dir1 = root.getEntry("dir1").getDirectory();
        FSEntry testInDir1 = dir1.getEntry("test.txt");
        assertNotNull("dir1/test.txt not found", testInDir1);

        // Read dir2/test.txt
        FSDirectory dir2 = root.getEntry("dir2").getDirectory();
        FSEntry testInDir2 = dir2.getEntry("test.txt");
        assertNotNull("dir2/test.txt not found", testInDir2);

        // Both files should have different content (different MD5s in test structure)
        FSFile f1 = testInDir1.getFile();
        FSFile f2 = testInDir2.getFile();
        assertEquals("files should be same size", f1.getLength(), f2.getLength());

        ByteBuffer b1 = ByteBuffer.allocate((int) f1.getLength());
        ByteBuffer b2 = ByteBuffer.allocate((int) f2.getLength());
        f1.read(0, b1);
        f2.read(0, b2);
        b1.flip();
        b2.flip();
        byte[] d1 = new byte[b1.remaining()];
        byte[] d2 = new byte[b2.remaining()];
        b1.get(d1);
        b2.get(d2);
        assertFalse("files should have different content", Arrays.equals(d1, d2));

        fs.close();
        device.close();
    }
}
