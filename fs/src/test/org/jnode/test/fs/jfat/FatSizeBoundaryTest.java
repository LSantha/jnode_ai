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

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystemFullException;
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

public class FatSizeBoundaryTest {

    private static final long LIMIT = 0xFFFFFFFFL;
    private static final long LIMIT_PLUS_ONE = 0x100000000L;

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
        diskFile = File.createTempFile("jfat-boundary-test", ".img");
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
    public void testWriteAtIntegerMaxValueIsFileSystemFull() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "maxint.bin");
            try {
                file.write(0x7FFFFFFFL, ByteBuffer.wrap(pattern(4)));
                fail("expected FileSystemFullException");
            } catch (FileSystemFullException e) {
                assertNotNull(e.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testWriteAtTwoGiBytesIsFileSystemFull() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "twogig.bin");
            try {
                file.write(0x80000000L, ByteBuffer.wrap(pattern(4)));
                fail("expected FileSystemFullException");
            } catch (FileSystemFullException e) {
                assertNotNull(e.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testWriteAtLimitIsFileSystemFull() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "limit.bin");
            try {
                file.write(LIMIT, ByteBuffer.wrap(pattern(4)));
                fail("expected FileSystemFullException");
            } catch (FileSystemFullException e) {
                assertNotNull(e.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testWriteAboveLimitThrowsIOException() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "above.bin");
            try {
                file.write(LIMIT_PLUS_ONE, ByteBuffer.wrap(pattern(4)));
                fail("expected IOException");
            } catch (IOException e) {
                assertLimitMessage(e);
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testSetLengthAtIntegerMaxValueIsFileSystemFull() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "lenmaxint.bin");
            try {
                file.setLength(0x7FFFFFFFL);
                fail("expected FileSystemFullException");
            } catch (FileSystemFullException e) {
                assertNotNull(e.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testSetLengthAtLimitIsFileSystemFull() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "lenlimit.bin");
            try {
                file.setLength(LIMIT);
                fail("expected FileSystemFullException");
            } catch (FileSystemFullException e) {
                assertNotNull(e.getMessage());
            }
        } finally {
            fs.close();
        }
    }

    @Test
    public void testSetLengthAboveLimitThrowsIOException() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = addEmptyFile(fs, "lenabove.bin");
            try {
                file.setLength(LIMIT_PLUS_ONE);
                fail("expected IOException");
            } catch (IOException e) {
                assertLimitMessage(e);
            }
        } finally {
            fs.close();
        }
    }

    private void assertLimitMessage(IOException e) {
        assertNotNull("exception message must not be null", e.getMessage());
        assertTrue("message must name the 4294967295 (0xFFFFFFFF) limit: " + e.getMessage(),
            e.getMessage().indexOf("0xFFFFFFFF") >= 0);
        assertTrue("message must name the offending value: " + e.getMessage(),
            e.getMessage().indexOf(String.valueOf(LIMIT_PLUS_ONE)) >= 0);
    }

    private FSFile addEmptyFile(FatFileSystem fs, String name) throws Exception {
        FSDirectory rootDir = fs.getRootEntry().getDirectory();
        return rootDir.addFile(name).getFile();
    }

    private byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(200L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }
}
