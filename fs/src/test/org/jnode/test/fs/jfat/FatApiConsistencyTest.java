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

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.jfat.BootSector;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatDirEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatMarshal;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.jnode.util.LittleEndian;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * API-consistency coverage for the JFAT classes that mix a low-level sector
 * buffer view with a decoded field view: {@link BootSector}, {@link FatDirEntry}
 * and {@link Fat#getClusterPosition(int)}.
 */
public class FatApiConsistencyTest {

    private FileDevice device;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
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
    }

    private static byte[] validFat32Sector() {
        byte[] sector = new byte[512];
        sector[0] = (byte) 0xEB;
        sector[1] = 0x3C;
        sector[2] = (byte) 0x90;
        LittleEndian.setInt16(sector, 11, 512);
        LittleEndian.setInt8(sector, 13, 2);
        LittleEndian.setInt16(sector, 14, 36);
        sector[16] = 2;
        LittleEndian.setInt16(sector, 17, 0);
        LittleEndian.setInt16(sector, 19, 0);
        sector[21] = (byte) 0xF8;
        LittleEndian.setInt16(sector, 22, 0);
        LittleEndian.setInt16(sector, 24, 63);
        LittleEndian.setInt16(sector, 26, 255);
        LittleEndian.setInt32(sector, 28, 0);
        LittleEndian.setInt32(sector, 32, 204800);
        LittleEndian.setInt32(sector, 36, 794);
        LittleEndian.setInt32(sector, 44, 2);
        sector[510] = 0x55;
        sector[511] = (byte) 0xAA;
        return sector;
    }

    private static byte[] validFat16Sector() {
        byte[] sector = new byte[512];
        sector[0] = (byte) 0xEB;
        sector[1] = 0x3C;
        sector[2] = (byte) 0x90;
        LittleEndian.setInt16(sector, 11, 512);
        LittleEndian.setInt8(sector, 13, 2);
        LittleEndian.setInt16(sector, 14, 1);
        sector[16] = 2;
        LittleEndian.setInt16(sector, 17, 224);
        LittleEndian.setInt16(sector, 19, 20000);
        sector[21] = (byte) 0xF8;
        LittleEndian.setInt16(sector, 22, 16);
        LittleEndian.setInt16(sector, 24, 63);
        LittleEndian.setInt16(sector, 26, 255);
        LittleEndian.setInt32(sector, 28, 0);
        LittleEndian.setInt32(sector, 32, 0);
        sector[510] = 0x55;
        sector[511] = (byte) 0xAA;
        return sector;
    }

    private FileDevice openTempDevice(String prefix) throws Exception {
        File tempFile = File.createTempFile(prefix, ".img");
        tempFile.deleteOnExit();
        FileDevice tempDevice = new FileDevice(tempFile, "rw");
        tempDevice.setLength(4 * 512L);
        return tempDevice;
    }

    @Test
    public void testFat32SetBPBWriteRoundTripAgreesWithBuffer() throws Exception {
        device = openTempDevice("jfat-bootsector-fat32");
        BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);

        BootSector bs = new BootSector(validFat32Sector());
        assertEquals(794, bs.getSectorsPerFat());

        bs.setBPB_FATSz32(548);
        assertTrue("set* must mark the sector dirty", bs.isDirty());

        bs.write(api, 0);
        assertFalse("write() must clear the dirty flag", bs.isDirty());

        byte[] onDisk = new byte[512];
        api.read(0, ByteBuffer.wrap(onDisk));
        assertEquals("setters must write through to the sector buffer",
            548L, LittleEndian.getUInt32(onDisk, 36));

        BootSector reread = new BootSector(512);
        reread.read(api);
        assertEquals("field view must agree with the buffer after a set* + write() round trip",
            548L, reread.getSectorsPerFat());
        assertTrue(reread.isFat32());
    }

    @Test
    public void testFat16SetBPBWriteRoundTripAgreesWithBuffer() throws Exception {
        device = openTempDevice("jfat-bootsector-fat16");
        BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);

        BootSector bs = new BootSector(validFat16Sector());
        assertTrue(bs.isFat16());
        assertEquals(16, bs.getSectorsPerFat());

        bs.setBPB_FATSz16(24);
        assertTrue("set* must mark the sector dirty", bs.isDirty());

        bs.write(api, 0);
        assertFalse("write() must clear the dirty flag", bs.isDirty());

        byte[] onDisk = new byte[512];
        api.read(0, ByteBuffer.wrap(onDisk));
        assertEquals("setters must write through to the sector buffer",
            24, LittleEndian.getUInt16(onDisk, 22));

        BootSector reread = new BootSector(512);
        reread.read(api);
        assertEquals("field view must agree with the buffer after a set* + write() round trip",
            24, reread.getSectorsPerFat());
        assertTrue(reread.isFat16());
    }

    @Test
    public void testFat32GetClusterPositionZeroNamesTheInvariant() throws Exception {
        File image = FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32");
        device = new FileDevice(image, "r");
        BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);

        Fat fat = Fat.create(api);
        assertTrue("expected the FAT32 test image", fat.isFat32());

        try {
            fat.getClusterPosition(0);
            fail("expected IllegalArgumentException naming the FAT32 invariant");
        } catch (IllegalArgumentException e) {
            assertTrue("message must name the invariant, not just the cluster number: "
                + e.getMessage(), e.getMessage().contains("FAT32"));
            assertTrue("message must name the root-directory invariant: "
                + e.getMessage(), e.getMessage().toLowerCase().contains("root"));
        }
    }

    private FatFileSystem mountFat32ReadOnly() throws Exception {
        FileSystemService fss = FileSystemTestUtils.createFSService(FatFileSystemType.class.getName());
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, true);
    }

    @Test
    public void testFatDirEntryDeleteMarksFreeAndKeepsIndex() throws Exception {
        File image = FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32");
        device = new FileDevice(image, "r");
        FatFileSystem fs = mountFat32ReadOnly();
        try {
            FatMarshal marshal = new FatMarshal(FatDirEntry.LENGTH);
            marshal.setUInt8(0, 'A');
            FatDirEntry entry = new FatDirEntry(fs, marshal, 3);
            assertFalse(entry.isFreeDirEntry());

            entry.delete();

            assertTrue("delete() must leave isFreeDirEntry() consistent with the buffer",
                entry.isFreeDirEntry());
            assertEquals("the buffer byte must be FREE",
                (byte) FatDirEntry.FREE, marshal.getByteBuffer().get(0));
            assertTrue("the buffer must be left dirty so the change is flushed",
                entry.isDirty());
            assertEquals("delete() must keep the slot index", 3, entry.getIndex());
        } finally {
            fs.close();
        }
    }

    @Test
    public void testFatDirEntryDeleteClearsLastDirEntryFlag() throws Exception {
        File image = FileSystemTestUtils.getTestFile("test/fs/jfat/test.fat32");
        device = new FileDevice(image, "r");
        FatFileSystem fs = mountFat32ReadOnly();
        try {
            FatMarshal marshal = new FatMarshal(FatDirEntry.LENGTH);
            FatDirEntry entry = new FatDirEntry(fs, marshal, 7, FatDirEntry.EOD);
            assertTrue(entry.isLastDirEntry());

            entry.delete();

            assertFalse("byte 0 overwritten with FREE is no longer EOD",
                entry.isLastDirEntry());
            assertTrue(entry.isFreeDirEntry());
            assertEquals(7, entry.getIndex());
        } finally {
            fs.close();
        }
    }
}
