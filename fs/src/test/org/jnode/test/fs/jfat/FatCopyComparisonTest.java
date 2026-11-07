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
import java.util.List;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
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

public class FatCopyComparisonTest {

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
    public void testIdenticalFat12CopiesReportNoDivergenceAndMountRW() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-copies-identical");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat12());
        Fat fat = fs.getFat();
        assertTrue("Identical FAT copies must not be flagged",
            fat.getDivergentFatSectors().isEmpty());
        assertTrue(fat.compareFatCopies().isEmpty());
        assertEquals(3, fat.get(2));
        int old = fat.set(9, 0xFFF);
        assertEquals(0, old);
        assertEquals(0xFFF, fat.get(9));
        fs.flush();
        fs.close();
        device.close();
        fs = mountRO(tempImage);
        assertEquals(0xFFF, fs.getFat().get(9));
        assertTrue(fs.getFat().getDivergentFatSectors().isEmpty());
        fs.close();
        device.close();
    }

    @Test
    public void testDivergentFat12CopyWarnsAndContinuesWithCopy0() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-copies-divergent");
        long fat1First = fatFirstOffset(tempImage, 1);
        corruptByte(tempImage, fat1First + 10, (byte) 0x55);
        FatFileSystem fs = mountRO(tempImage);
        Fat fat = fs.getFat();
        assertNotNull("Mount must continue with copy 0 despite divergence", fs);
        List<Long> divergent = fat.getDivergentFatSectors();
        assertEquals(1, divergent.size());
        assertEquals(Long.valueOf(fat.getFirstSector(1)), divergent.get(0));
        assertEquals(divergent, fat.compareFatCopies());
        assertEquals(3, fat.get(2));
        assertEquals(0xFFF, fat.get(7));
        fs.close();
        device.close();
    }

    @Test
    public void testDivergentFat16CopyWarnsAndContinuesWithCopy0() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-copies-divergent");
        long fat1First = fatFirstOffset(tempImage, 1);
        corruptByte(tempImage, fat1First + 10, (byte) 0xA5);
        FatFileSystem fs = mountRO(tempImage);
        assertTrue(fs.getBootSector().isFat16());
        Fat fat = fs.getFat();
        List<Long> divergent = fat.getDivergentFatSectors();
        assertEquals(1, divergent.size());
        assertEquals(Long.valueOf(fat.getFirstSector(1)), divergent.get(0));
        assertNotNull(fs.getRootEntry().getDirectory().getEntry("test.txt"));
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

    private long fatFirstOffset(File file, int fatNum) throws Exception {
        FileDevice probe = new FileDevice(file, "r");
        try {
            BlockDeviceAPI api = probe.getAPI(BlockDeviceAPI.class);
            BootSector bs = new BootSector(512);
            bs.read(api);
            long sector = (long) bs.getNrReservedSectors() + bs.getSectorsPerFat() * fatNum;
            return sector * bs.getBytesPerSector();
        } finally {
            probe.close();
        }
    }

    private void corruptByte(File file, long offset, byte value) throws Exception {
        FileDevice probe = new FileDevice(file, "rw");
        try {
            BlockDeviceAPI api = probe.getAPI(BlockDeviceAPI.class);
            byte[] original = new byte[1];
            api.read(offset, ByteBuffer.wrap(original));
            byte flipped = (byte) (original[0] ^ 0xFF);
            if (flipped == value) {
                flipped = (byte) (flipped ^ 0x0F);
            }
            api.write(offset, ByteBuffer.wrap(new byte[]{flipped}));
            api.flush();
        } finally {
            probe.close();
        }
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
