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

public class FatBeyondEofWriteTest {

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
        diskFile = File.createTempFile("jfat-beyond-eof-test", ".img");
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
    public void testBeyondEofWriteOnEmptyFile() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile("beyond.bin").getFile();
        file.write(5000, ByteBuffer.wrap("XY".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        FSEntry entry = rootDir.getEntry("beyond.bin");
        assertNotNull(entry);
        FSFile readFile = entry.getFile();
        assertEquals(5002, readFile.getLength());
        byte[] data = readAll(readFile);
        assertEquals((byte) 'X', data[5000]);
        assertEquals((byte) 'Y', data[5001]);
        for (int i = 0; i < 5000; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        fs.close();
    }

    @Test
    public void testBeyondEofWriteOnNonEmptyFileWarm() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile("warm.bin").getFile();
        file.write(0, ByteBuffer.wrap("1234567890".getBytes()));
        file.flush();
        fs.flush();
        file.write(5000, ByteBuffer.wrap("XY".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        FSFile readFile = rootDir.getEntry("warm.bin").getFile();
        assertEquals(5002, readFile.getLength());
        byte[] data = readAll(readFile);
        assertTrue(Arrays.equals("1234567890".getBytes(), Arrays.copyOf(data, 10)));
        for (int i = 10; i < 5000; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        assertEquals((byte) 'X', data[5000]);
        assertEquals((byte) 'Y', data[5001]);
        fs.close();
    }

    @Test
    public void testBeyondEofWriteOnNonEmptyFileColdMount() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSFile file = rootDir.addFile("cold.bin").getFile();
        file.write(0, ByteBuffer.wrap("1234567890".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadWrite();
        rootDir = fs.getRootEntry().getDirectory();
        file = rootDir.getEntry("cold.bin").getFile();
        file.write(5000, ByteBuffer.wrap("XY".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        FSFile readFile = rootDir.getEntry("cold.bin").getFile();
        assertEquals(5002, readFile.getLength());
        byte[] data = readAll(readFile);
        assertTrue(Arrays.equals("1234567890".getBytes(), Arrays.copyOf(data, 10)));
        for (int i = 10; i < 5000; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        assertEquals((byte) 'X', data[5000]);
        assertEquals((byte) 'Y', data[5001]);
        fs.close();
    }

    @Test
    public void testBeyondEofWriteOnFat16Warm() throws Exception {
        copyFixture("test/fs/jfat/test.fat16");
        FatFileSystem fs = openFixtureReadWrite();
        assertTrue(fs.getBootSector().isFat16());
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSDirectory dir1 = rootDir.getEntry("dir1").getDirectory();
        FSFile file = dir1.getEntry("test.txt").getFile();
        byte[] original = readAll(file);
        assertEquals(18, original.length);
        byte[] rootBefore = readRootRegion(fs);
        file.write(0, ByteBuffer.wrap("1234567890".getBytes()));
        file.flush();
        fs.flush();
        file.write(5000, ByteBuffer.wrap("XY".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        assertNotNull("dir1 should survive", rootDir.getEntry("dir1"));
        assertNotNull("dir2 should survive", rootDir.getEntry("dir2"));
        dir1 = rootDir.getEntry("dir1").getDirectory();
        FSEntry entry = dir1.getEntry("test.txt");
        assertNotNull("test.txt should survive", entry);
        FSFile readFile = entry.getFile();
        byte[] data = readAll(readFile);
        assertEquals(5002, data.length);
        assertTrue(Arrays.equals("1234567890".getBytes(), Arrays.copyOf(data, 10)));
        assertEquals((byte) 'X', data[5000]);
        assertEquals((byte) 'Y', data[5001]);
        assertTrue("FAT16 root region changed", Arrays.equals(rootBefore, readRootRegion(fs)));
        fs.close();
    }

    @Test
    public void testBeyondEofWriteOnFat16ColdMount() throws Exception {
        copyFixture("test/fs/jfat/test.fat16");
        FatFileSystem fs = openFixtureReadWrite();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        FSDirectory dir1 = rootDir.getEntry("dir1").getDirectory();
        FSFile file = dir1.getEntry("test.txt").getFile();
        file.write(0, ByteBuffer.wrap("1234567890".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadWrite();
        rootDir = fs.getRootEntry().getDirectory();
        dir1 = rootDir.getEntry("dir1").getDirectory();
        file = dir1.getEntry("test.txt").getFile();
        file.write(5000, ByteBuffer.wrap("XY".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        assertNotNull(rootDir.getEntry("dir1"));
        assertNotNull(rootDir.getEntry("dir2"));
        dir1 = rootDir.getEntry("dir1").getDirectory();
        FSFile readFile = dir1.getEntry("test.txt").getFile();
        byte[] data = readAll(readFile);
        assertEquals(5002, data.length);
        assertTrue(Arrays.equals("1234567890".getBytes(), Arrays.copyOf(data, 10)));
        assertEquals((byte) 'X', data[5000]);
        assertEquals((byte) 'Y', data[5001]);
        fs.close();
    }

    private byte[] readRootRegion(FatFileSystem fs) throws Exception {
        BootSector bs = fs.getBootSector();
        long offset = bs.getFirstRootDirSector() * (long) bs.getBytesPerSector();
        int size = (int) (bs.getNrRootDirEntries() * 32);
        byte[] data = new byte[size];
        fs.getApi().read(offset, ByteBuffer.wrap(data));
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

    private void copyFixture(String name) throws Exception {
        File src = FileSystemTestUtils.getTestFile(name);
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(diskFile);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }

    private FatFileSystem openFixtureReadWrite() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, false);
    }

    private FatFileSystem openReadWrite() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, false);
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
