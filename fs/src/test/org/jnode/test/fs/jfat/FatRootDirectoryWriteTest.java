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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatRootDirectoryWriteTest {

    private FileDevice device;
    private FileSystemService fss;
    private File tempImage;

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
            }
            device = null;
        }
        if (tempImage != null && tempImage.exists()) {
            tempImage.delete();
            tempImage = null;
        }
    }

    @Test
    public void testFat16RootFilePersistsAcrossRemount() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-root");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat16());

        byte[] before = readRawRootRegion(fs);
        assertFalse(containsRootName(before, "AAA"));

        FSDirectory root = fs.getRootEntry().getDirectory();
        byte[] data = "0123456789".getBytes("US-ASCII");
        FSFile file = root.addFile("aaa.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        byte[] after = readRawRootRegion(fs);
        assertFalse("Fixed root region must change on write", Arrays.equals(before, after));
        assertTrue("Fixed root region must contain AAA entry", containsRootName(after, "AAA"));

        fs.close();
        device.close();

        fs = mountRO(tempImage);
        root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry("aaa.bin");
        assertNotNull("aaa.bin missing after remount", entry);
        assertTrue(entry.isFile());
        assertEquals(data.length, entry.getFile().getLength());
        ByteBuffer buf = ByteBuffer.allocate(data.length);
        entry.getFile().read(0, buf);
        assertArrayEquals(data, buf.array());

        FSEntry original = root.getEntry("test.txt");
        assertNotNull("Original test.txt must survive", original);
        assertEquals(18, original.getFile().getLength());

        fs.close();
        device.close();
    }

    @Test
    public void testFat16RootDeletePersistsAcrossRemount() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-root-del");
        FatFileSystem fs = mountRW(tempImage);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("del.bin").getFile();
        file.write(0, ByteBuffer.wrap("x".getBytes("US-ASCII")));
        file.flush();
        fs.flush();
        assertNotNull(root.getEntry("del.bin"));

        root.remove("del.bin");
        fs.flush();

        fs.close();
        device.close();

        fs = mountRO(tempImage);
        root = fs.getRootEntry().getDirectory();
        assertNull("del.bin must stay deleted after remount", root.getEntry("del.bin"));
        assertNotNull("test.txt must survive delete", root.getEntry("test.txt"));

        fs.close();
        device.close();
    }

    @Test
    public void testFat16SubdirectoryWriteStillPersists() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-sub");
        FatFileSystem fs = mountRW(tempImage);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSDirectory dir1 = root.getEntry("dir1").getDirectory();
        byte[] data = "subdata".getBytes("US-ASCII");
        FSFile file = dir1.addFile("sub.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        fs.close();
        device.close();

        fs = mountRO(tempImage);
        FSDirectory dir1Again = fs.getRootEntry().getDirectory().getEntry("dir1").getDirectory();
        FSEntry entry = dir1Again.getEntry("sub.bin");
        assertNotNull("dir1/sub.bin missing after remount", entry);
        ByteBuffer buf = ByteBuffer.allocate(data.length);
        entry.getFile().read(0, buf);
        assertArrayEquals(data, buf.array());

        fs.close();
        device.close();
    }

    @Test
    public void testFat32RootFileStillPersists() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat32", "jfat32-root");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat32());

        FSDirectory root = fs.getRootEntry().getDirectory();
        byte[] data = "fat32root".getBytes("US-ASCII");
        FSFile file = root.addFile("fat32.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        fs.close();
        device.close();

        fs = mountRO(tempImage);
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("fat32.bin");
        assertNotNull("fat32.bin missing after remount", entry);
        ByteBuffer buf = ByteBuffer.allocate(data.length);
        entry.getFile().read(0, buf);
        assertArrayEquals(data, buf.array());

        fs.close();
        device.close();
    }

    @Test
    public void testFat12RootFilePersistsAcrossRemount() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-root-rw");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat12());

        FSDirectory root = fs.getRootEntry().getDirectory();
        byte[] data = "0123456789".getBytes("US-ASCII");
        FSFile file = root.addFile("new.bin").getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        fs.close();
        device.close();

        fs = mountRO(tempImage);
        root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry("new.bin");
        assertNotNull("new.bin missing after remount on FAT12", entry);
        assertEquals(data.length, entry.getFile().getLength());
        ByteBuffer buf = ByteBuffer.allocate(data.length);
        entry.getFile().read(0, buf);
        assertArrayEquals(data, buf.array());
        assertNotNull("MULTI.TXT must survive", root.getEntry(Fat12ImageBuilder.MULTI_NAME));

        fs.close();
        device.close();
    }

    @Test
    public void testFat12SetPreservesNeighbourEntries() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-set");
        FatFileSystem fs = mountRW(tempImage);
        Fat fat = fs.getFat();
        assertEquals(0xFFF, fat.get(7));
        assertEquals(7, fat.get(8));
        assertEquals(0, fat.get(9));
        assertEquals(0, fat.get(10));

        int old = fat.set(9, 0xFFF);
        assertEquals(0, old);
        assertEquals(0xFFF, fat.get(9));
        assertEquals(7, fat.get(8));
        assertEquals(0, fat.get(10));

        old = fat.set(10, 0xABC);
        assertEquals(0, old);
        assertEquals(0xABC, fat.get(10));
        assertEquals(0xFFF, fat.get(9));

        fs.flush();
        fs.close();
        device.close();

        fs = mountRO(tempImage);
        fat = fs.getFat();
        assertEquals(0xFFF, fat.get(9));
        assertEquals(0xABC, fat.get(10));
        assertEquals(7, fat.get(8));

        fs.close();
        device.close();
    }

    @Test
    public void testFat12SetStraddlingEntry() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-straddle");
        FatFileSystem fs = mountRW(tempImage);
        Fat fat = fs.getFat();
        assertEquals(341, fat.get(340));
        assertEquals(342, fat.get(341));
        assertEquals(0xFFF, fat.get(342));

        fat.set(341, 0x123);
        assertEquals(0x123, fat.get(341));
        assertEquals(341, fat.get(340));
        assertEquals(0xFFF, fat.get(342));

        fs.flush();
        fs.close();
        device.close();

        fs = mountRO(tempImage);
        fat = fs.getFat();
        assertEquals(0x123, fat.get(341));
        assertEquals(341, fat.get(340));
        assertEquals(0xFFF, fat.get(342));

        fs.close();
        device.close();
    }

    private FatFileSystem mountRW(File file) throws Exception {
        device = new FileDevice(file, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, false);
    }

    private FatFileSystem mountRO(File file) throws Exception {
        device = new FileDevice(file, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, true);
    }

    private byte[] readRawRootRegion(FatFileSystem fs) throws Exception {
        BootSector bs = fs.getBootSector();
        long offset = bs.getFirstRootDirSector() * bs.getBytesPerSector();
        int length = (int) (bs.getNrRootDirEntries() * 32);
        byte[] data = new byte[length];
        BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);
        api.read(offset, ByteBuffer.wrap(data));
        return data;
    }

    private boolean containsRootName(byte[] rootRegion, String name) {
        for (int i = 0; i + name.length() <= rootRegion.length; i += 32) {
            boolean match = true;
            for (int j = 0; j < name.length(); j++) {
                if (rootRegion[i + j] != (byte) name.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private File copyFixture(String fixture, String prefix) throws Exception {
        File source = FileSystemTestUtils.getTestFile(fixture);
        File copy = File.createTempFile(prefix, ".img");
        copy.deleteOnExit();
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(copy);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return copy;
    }
}
