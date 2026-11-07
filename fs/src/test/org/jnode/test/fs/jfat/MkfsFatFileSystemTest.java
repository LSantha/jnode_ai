/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatMarshal;
import org.jnode.fs.jfat.FatShortDirEntry;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.jnode.util.LittleEndian;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class MkfsFatFileSystemTest {

    private static final String FILE_TXT = "FILE.TXT";
    private static final String FAT16_CONTENT = "Hello FAT16 mkfs.fat file 0123456789!\n";
    private static final String FAT12_CONTENT = "Hello FAT12 floppy mkfs.fat file 0123456789\n";

    private FileDevice device;
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
    }

    @After
    public void tearDown() throws Exception {
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
            }
        }
    }

    @Test
    public void testMkfsFat16WithLabel() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat16label");

        assertTrue("Should be FAT16", fs.getBootSector().isFat16());
        assertEquals("LABEL", fs.getRootEntry().getLabel().trim());

        assertRootFile(fs, FAT16_CONTENT);
        fs.close();
    }

    @Test
    public void testMkfsFat16WithoutLabel() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat16nolabel");

        assertTrue("Should be FAT16", fs.getBootSector().isFat16());

        assertRootFile(fs, FAT16_CONTENT);
        fs.close();
    }

    @Test
    public void testMkfsFat12Floppy() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat12");

        assertTrue("Should be FAT12", fs.getBootSector().isFat12());

        assertRootFile(fs, FAT12_CONTENT);
        fs.close();
    }

    @Test
    public void testFat16ClusterHighIgnored() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat16label");

        FatShortDirEntry entry = decodeEntry(fs, 0x6463, 7);
        assertEquals("FAT16 start cluster must be FstClusLo only", 7, entry.getStartCluster());
        fs.close();
    }

    @Test
    public void testFat12ClusterHighIgnored() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat12");

        FatShortDirEntry entry = decodeEntry(fs, 0x6463, 5);
        assertEquals("FAT12 start cluster must be FstClusLo only", 5, entry.getStartCluster());
        fs.close();
    }

    @Test
    public void testFat32ClusterHighStillRejected() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.fat32");

        assertTrue("Should be FAT32", fs.getBootSector().isFat32());
        try {
            decodeEntry(fs, 0x1000, 7);
            fail("FAT32 FstClusHi above 28 bits should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue("Unexpected message: " + e.getMessage(),
                e.getMessage().startsWith("FstClusHi too large"));
        }
        fs.close();
    }

    private FatFileSystem mount(String fixture) throws Exception {
        device = new FileDevice(FileSystemTestUtils.getTestFile(fixture), "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, true);
    }

    private void assertRootFile(FatFileSystem fs, String content) throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();

        List<String> names = new ArrayList<String>();
        Iterator<? extends FSEntry> it = root.iterator();
        while (it.hasNext()) {
            names.add(it.next().getName());
        }
        assertEquals("Root should list exactly FILE.TXT", 1, names.size());
        assertTrue("Root should contain FILE.TXT", names.contains(FILE_TXT));

        FSEntry entry = root.getEntry(FILE_TXT);
        assertNotNull("FILE.TXT should exist", entry);
        assertTrue("FILE.TXT should be a file", entry.isFile());
        assertEquals(content.length(), entry.getFile().getLength());

        ByteBuffer buf = ByteBuffer.allocate(content.length());
        entry.getFile().read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertEquals(content, new String(data, "US-ASCII"));
    }

    private FatShortDirEntry decodeEntry(FatFileSystem fs, int clusterHi, int clusterLo) throws Exception {
        byte[] raw = new byte[32];
        raw[0] = 'F';
        raw[1] = 'I';
        raw[2] = 'L';
        raw[3] = 'E';
        raw[8] = 'T';
        raw[9] = 'X';
        raw[10] = 'T';
        for (int i = 4; i < 8; i++) {
            raw[i] = ' ';
        }
        raw[11] = 0x20;
        int dosDate = (0 << 9) | (1 << 5) | 1;
        LittleEndian.setInt16(raw, 14, 0);
        LittleEndian.setInt16(raw, 16, dosDate);
        LittleEndian.setInt16(raw, 18, dosDate);
        LittleEndian.setInt16(raw, 20, clusterHi);
        LittleEndian.setInt16(raw, 22, 0);
        LittleEndian.setInt16(raw, 24, dosDate);
        LittleEndian.setInt16(raw, 26, clusterLo);
        LittleEndian.setInt32(raw, 28, 38);

        return new FatShortDirEntry(fs, new FatMarshal(raw), 0);
    }
}
