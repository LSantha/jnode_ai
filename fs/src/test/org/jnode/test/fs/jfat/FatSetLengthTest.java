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
import java.io.RandomAccessFile;
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

public class FatSetLengthTest {

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
        diskFile = File.createTempFile("jfat-setlength-test", ".img");
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
    public void testSameClusterShrink() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("shrink.bin").getFile();
        byte[] data = pattern(27);
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        file.setLength(5);
        assertEquals(5, file.getLength());
        byte[] read = readAll(file);
        assertEquals(5, read.length);
        assertTrue(Arrays.equals(Arrays.copyOf(data, 5), read));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("shrink.bin").getFile();
        assertEquals(5, readFile.getLength());
        assertTrue(Arrays.equals(Arrays.copyOf(data, 5), readAll(readFile)));
        fs.close();
    }

    @Test
    public void testCrossClusterShrink() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("cross.bin").getFile();
        byte[] data = pattern(5000);
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        file.setLength(3000);
        assertEquals(3000, file.getLength());
        assertTrue(Arrays.equals(Arrays.copyOf(data, 3000), readAll(file)));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("cross.bin").getFile();
        assertEquals(3000, readFile.getLength());
        assertTrue(Arrays.equals(Arrays.copyOf(data, 3000), readAll(readFile)));
        fs.close();
    }

    @Test
    public void testGrowZeroFillsNewRange() throws Exception {
        prefill(68L * 1024 * 1024);
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("grow.bin").getFile();
        file.setLength(4096);
        assertEquals(4096, file.getLength());
        assertAllZero(readAll(file));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("grow.bin").getFile();
        assertEquals(4096, readFile.getLength());
        assertAllZero(readAll(readFile));
        fs.close();
    }

    @Test
    public void testGrowAfterTruncateToZeroZeroFills() throws Exception {
        prefill(68L * 1024 * 1024);
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("regrow.bin").getFile();
        file.write(0, ByteBuffer.wrap(pattern(2048)));
        file.flush();
        file.setLength(0);
        assertEquals(0, file.getLength());
        file.setLength(2048);
        assertEquals(2048, file.getLength());
        assertAllZero(readAll(file));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("regrow.bin").getFile();
        assertEquals(2048, readFile.getLength());
        assertAllZero(readAll(readFile));
        fs.close();
    }

    @Test
    public void testGrowNonAlignedZeroFills() throws Exception {
        prefill(68L * 1024 * 1024);
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("grow3000.bin").getFile();
        file.setLength(3000);
        assertEquals(3000, file.getLength());
        assertAllZero(readAll(file));
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("grow3000.bin").getFile();
        assertEquals(3000, readFile.getLength());
        assertAllZero(readAll(readFile));
        fs.close();
    }

    @Test
    public void testGrowWithinClusterZeroFills() throws Exception {
        prefill(68L * 1024 * 1024);
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile("within.bin").getFile();
        byte[] data = pattern(27);
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        file.setLength(500);
        assertEquals(500, file.getLength());
        byte[] read = readAll(file);
        assertTrue(Arrays.equals(data, Arrays.copyOf(read, 27)));
        for (int i = 27; i < read.length; i++) {
            assertEquals("grown byte " + i, (byte) 0, read[i]);
        }
        file.flush();
        fs.flush();
        fs.close();

        fs = openReadOnly();
        FSFile readFile = fs.getRootEntry().getDirectory().getEntry("within.bin").getFile();
        assertEquals(500, readFile.getLength());
        byte[] persisted = readAll(readFile);
        assertTrue(Arrays.equals(data, Arrays.copyOf(persisted, 27)));
        for (int i = 27; i < persisted.length; i++) {
            assertEquals("persisted grown byte " + i, (byte) 0, persisted[i]);
        }
        fs.close();
    }

    private byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private void assertAllZero(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            assertEquals("byte " + i, (byte) 0, data[i]);
        }
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

    private void prefill(long size) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(diskFile, "rw");
        try {
            raf.setLength(size);
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 0xAB);
            raf.seek(0);
            long remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                raf.write(chunk, 0, n);
                remaining -= n;
            }
        } finally {
            raf.close();
        }
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
