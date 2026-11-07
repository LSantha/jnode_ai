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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatMarshal;
import org.jnode.fs.jfat.FatRootDirectory;
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
        }
        if (tempImage != null) {
            tempImage.delete();
            tempImage = null;
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

    @Test
    public void testRootDirectoryBoundaryThrowsNoSuchElement() throws Exception {
        FatFileSystem fs = mount("test/fs/jfat/test.mkfsfat16label");

        FatRootDirectory root = fs.getRootEntry();
        int nrRootDirEntries = (int) fs.getBootSector().getNrRootDirEntries();
        assertTrue(nrRootDirEntries > 0);
        try {
            root.getFatDirEntry(nrRootDirEntries, false);
            fail("Reading at nrRootDirEntries must signal end of root directory");
        } catch (NoSuchElementException expected) {
        }
        fs.close();
    }

    @Test
    public void testFullRootDirectoryWithoutEndMarker() throws Exception {
        tempImage = createFullRootImage();
        FatFileSystem fs = mount(tempImage);

        assertTrue("Should be FAT12", fs.getBootSector().isFat12());
        int nrRootDirEntries = (int) fs.getBootSector().getNrRootDirEntries();

        FSDirectory root = fs.getRootEntry().getDirectory();
        List<String> names = new ArrayList<String>();
        Iterator<? extends FSEntry> it = root.iterator();
        while (it.hasNext()) {
            names.add(it.next().getName());
        }
        assertEquals("Full root should list every file entry", nrRootDirEntries - 1, names.size());
        assertTrue("Root should contain FILE.TXT", names.contains(FILE_TXT));
        assertFalse("Past-root data sector must not be listed as an entry", names.contains("BOGUS.TXT"));
        fs.close();
    }

    private FatFileSystem mount(String fixture) throws Exception {
        return mount(FileSystemTestUtils.getTestFile(fixture));
    }

    private FatFileSystem mount(File file) throws Exception {
        device = new FileDevice(file, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, true);
    }

    private File createFullRootImage() throws Exception {
        File source = FileSystemTestUtils.getTestFile("test/fs/jfat/test.mkfsfat12");
        File copy = File.createTempFile("jnode-fullroot", ".img");
        copy.deleteOnExit();

        FileInputStream in = new FileInputStream(source);
        try {
            FileOutputStream out = new FileOutputStream(copy);
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

        RandomAccessFile raf = new RandomAccessFile(copy, "rw");
        try {
            byte[] boot = new byte[512];
            raf.seek(0);
            raf.readFully(boot);
            int bytesPerSector = (boot[11] & 0xFF) | ((boot[12] & 0xFF) << 8);
            int reservedSectors = (boot[14] & 0xFF) | ((boot[15] & 0xFF) << 8);
            int nrFats = boot[16] & 0xFF;
            int nrRootDirEntries = (boot[17] & 0xFF) | ((boot[18] & 0xFF) << 8);
            int sectorsPerFat = (boot[22] & 0xFF) | ((boot[23] & 0xFF) << 8);
            int rootDirSectors = ((nrRootDirEntries * 32) + (bytesPerSector - 1)) / bytesPerSector;
            long rootOffset = (long) (reservedSectors + nrFats * sectorsPerFat) * bytesPerSector;
            long firstDataOffset = rootOffset + (long) rootDirSectors * bytesPerSector;

            byte[] template = new byte[32];
            raf.seek(rootOffset + 32);
            raf.readFully(template);

            for (int i = 2; i < nrRootDirEntries; i++) {
                byte[] entry = new byte[32];
                System.arraycopy(template, 0, entry, 0, 32);
                String number = Integer.toString(i);
                while (number.length() < 7) {
                    number = "0" + number;
                }
                String name = "F" + number;
                for (int j = 0; j < 8; j++) {
                    entry[j] = (byte) name.charAt(j);
                }
                entry[8] = 'T';
                entry[9] = 'X';
                entry[10] = 'T';
                raf.seek(rootOffset + 32L * i);
                raf.write(entry);
            }

            byte[] bogus = new byte[32];
            System.arraycopy(template, 0, bogus, 0, 32);
            String bogusName = "BOGUS   ";
            for (int j = 0; j < 8; j++) {
                bogus[j] = (byte) bogusName.charAt(j);
            }
            bogus[8] = 'T';
            bogus[9] = 'X';
            bogus[10] = 'T';
            bogus[20] = 'c';
            bogus[21] = 'd';
            bogus[26] = 2;
            bogus[27] = 0;
            raf.seek(firstDataOffset);
            raf.write(bogus);
        } finally {
            raf.close();
        }
        return copy;
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
