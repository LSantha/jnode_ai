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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatEntry;
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

/**
 * Pins the eager gap-zeroing contract of the length-less
 * {@code FatChain.write(offset, src)} overload: the overload records no deferred-zero
 * holes (it serves callers whose chain carries live content without a file length,
 * e.g. FatDirectory), so every cluster it links for a gap-extending write must be
 * zeroed eagerly. Stale device bytes must never be readable through the grown gap.
 */
public class FatLengthlessWriteGapTest {

    private static final long IMAGE_LENGTH = 32L * 1024 * 1024;

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
        diskFile = File.createTempFile("jfat-lengthless-gap-test", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            try {
                fileDevice.close();
            } catch (Exception e) {
            }
            fileDevice = null;
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testLengthlessWriteZeroesGrownGap() throws Exception {
        prefill();
        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystem fs = new FatFileSystemFormatter(ClusterSize._1Kb).format(fileDevice);

        FSFile file = fs.getRootEntry().getDirectory().addFile("gap.bin").getFile();
        FSEntry entry = fs.getRootEntry().getDirectory().getEntry("gap.bin");
        FatChain chain = ((FatEntry) entry).getChain();

        byte[] payload = new byte[] {1, 2, 3, 4};
        chain.write(5000, ByteBuffer.wrap(payload));

        byte[] data = new byte[5 * 1024];
        chain.read(0, ByteBuffer.wrap(data));
        for (int i = 0; i < 5000; i++) {
            assertEquals("gap byte " + i, (byte) 0, data[i]);
        }
        assertEquals((byte) 1, data[5000]);
        assertEquals((byte) 2, data[5001]);
        assertEquals((byte) 3, data[5002]);
        assertEquals((byte) 4, data[5003]);
        for (int i = 5004; i < data.length; i++) {
            assertEquals("tail byte " + i, (byte) 0, data[i]);
        }
        file.flush();
        fs.flush();
        fs.close();
    }

    private void prefill() throws IOException {
        RandomAccessFile raf = new RandomAccessFile(diskFile, "rw");
        try {
            raf.setLength(IMAGE_LENGTH);
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 0xAB);
            raf.seek(0);
            long remaining = IMAGE_LENGTH;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                raf.write(chunk, 0, n);
                remaining -= n;
            }
        } finally {
            raf.close();
        }
    }
}
