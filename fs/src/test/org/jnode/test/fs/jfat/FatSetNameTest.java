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
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
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

public class FatSetNameTest {

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
        diskFile = File.createTempFile("jfat-setname-test", ".img");
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
    public void testRenameFileSurvivesRemount() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.addFile("old.txt");
        byte[] data = pattern(5);
        FSFile file = entry.getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        entry.setName("new.txt");
        assertEquals("new.txt", entry.getName());
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSDirectory reopened = fs.getRootEntry().getDirectory();
        assertNull(reopened.getEntry("old.txt"));
        FSEntry renamed = reopened.getEntry("new.txt");
        assertNotNull(renamed);
        assertEquals("new.txt", renamed.getName());
        FSFile readFile = renamed.getFile();
        assertEquals(data.length, readFile.getLength());
        assertTrue(Arrays.equals(data, readAll(readFile)));
        fs.close();
    }

    @Test
    public void testRenameUpdatesDirectoryCache() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.addFile("old.txt");
        byte[] data = pattern(5);
        FSFile file = entry.getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        assertNotNull(root.getEntry("old.txt"));

        entry.setName("new.txt");

        assertNull(root.getEntry("old.txt"));
        FSEntry renamed = root.getEntry("new.txt");
        assertNotNull(renamed);
        assertEquals("new.txt", renamed.getName());
        assertEquals("new.txt", entry.getName());
        assertTrue(Arrays.equals(data, readAll(entry.getFile())));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        assertNull(fs.getRootEntry().getDirectory().getEntry("old.txt"));
        assertNotNull(fs.getRootEntry().getDirectory().getEntry("new.txt"));
        fs.close();
    }

    @Test
    public void testRenameToExistingNameFails() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry first = root.addFile("first.txt");
        FSEntry second = root.addFile("second.txt");
        byte[] firstData = pattern(16);
        byte[] secondData = pattern(16);
        for (int i = 0; i < secondData.length; i++) {
            secondData[i] = (byte) (0xFF - i);
        }
        first.getFile().write(0, ByteBuffer.wrap(firstData));
        second.getFile().write(0, ByteBuffer.wrap(secondData));
        first.getFile().flush();
        second.getFile().flush();

        try {
            first.setName("second.txt");
            fail("expected an IOException");
        } catch (IOException ex) {
            assertTrue("expected an already exists error, got: " + ex.getMessage(),
                ex.getMessage().toLowerCase().contains("already exists"));
        }

        assertEquals("first.txt", first.getName());
        assertEquals("second.txt", second.getName());
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSDirectory reopened = fs.getRootEntry().getDirectory();
        FSFile firstRead = reopened.getEntry("first.txt").getFile();
        FSFile secondRead = reopened.getEntry("second.txt").getFile();
        assertTrue(Arrays.equals(firstData, readAll(firstRead)));
        assertTrue(Arrays.equals(secondData, readAll(secondRead)));
        fs.close();
    }

    @Test
    public void testRenameLongFileNameRewritesLfnEntries() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        String oldLongName = "some-very-long-file-name-for-jfat-rename.txt";
        String newLongName = "another-very-long-file-name-for-jfat-rename.txt";
        FSEntry entry = root.addFile(oldLongName);
        byte[] data = pattern(37);
        entry.getFile().write(0, ByteBuffer.wrap(data));
        entry.getFile().flush();

        entry.setName(newLongName);
        assertEquals(newLongName, entry.getName());
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSDirectory reopened = fs.getRootEntry().getDirectory();
        assertNull(reopened.getEntry(oldLongName));
        FSEntry renamed = reopened.getEntry(newLongName);
        assertNotNull(renamed);
        assertEquals(newLongName, renamed.getName());
        FSFile readFile = renamed.getFile();
        assertEquals(data.length, readFile.getLength());
        assertTrue(Arrays.equals(data, readAll(readFile)));
        fs.close();
    }

    @Test
    public void testRenameDirectory() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry dirEntry = root.addDirectory("subdir");
        FSDirectory subdir = dirEntry.getDirectory();
        FSEntry inner = subdir.addFile("inner.txt");
        byte[] data = pattern(11);
        inner.getFile().write(0, ByteBuffer.wrap(data));
        inner.getFile().flush();

        dirEntry.setName("renamed");
        assertEquals("renamed", dirEntry.getName());
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSDirectory reopened = fs.getRootEntry().getDirectory();
        assertNull(reopened.getEntry("subdir"));
        FSEntry renamedDir = reopened.getEntry("renamed");
        assertNotNull(renamedDir);
        assertTrue(renamedDir.isDirectory());
        FSEntry innerEntry = renamedDir.getDirectory().getEntry("inner.txt");
        assertNotNull(innerEntry);
        assertTrue(Arrays.equals(data, readAll(innerEntry.getFile())));
        fs.close();
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

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }

    private FatFileSystem openReadOnly() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        fileDevice = new FileDevice(diskFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, true);
    }
}
