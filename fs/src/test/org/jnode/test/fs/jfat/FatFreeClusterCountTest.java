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

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatFreeClusterCountTest {

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
    public void testCounterMatchesScanOnFat16Fixture() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat16", "jfat16-freecount");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat16());
        runSequence(fs);
        fs.close();
    }

    @Test
    public void testCounterMatchesScanOnFat32Fixture() throws Exception {
        tempImage = copyFixture("test/fs/jfat/test.fat32", "jfat32-freecount");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat32());
        runSequence(fs);
        fs.close();
    }

    @Test
    public void testCounterMatchesScanOnFat12Image() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat12-freecount");
        FatFileSystem fs = mountRW(tempImage);
        assertTrue(fs.getBootSector().isFat12());
        Fat fat = fs.getFat();
        assertCounterMatches(fs, "fat12 mount");
        int mountCount = fat.getFreeClusterCount();
        int straddle = fat.get(341);
        assertTrue(straddle != 0);
        fat.set(341, straddle);
        assertCounterMatches(fs, "fat12 no-op set on straddling entry");
        fat.set(341, 0);
        assertCounterMatches(fs, "fat12 free straddling entry");
        assertEquals(mountCount + 1, fat.getFreeClusterCount());
        fat.set(341, straddle);
        assertCounterMatches(fs, "fat12 reuse straddling entry");
        assertEquals(mountCount, fat.getFreeClusterCount());
        runSequence(fs);
        fs.close();
    }

    private void runSequence(FatFileSystem fs) throws Exception {
        Fat fat = fs.getFat();
        assertCounterMatches(fs, "mount");
        int mountCount = fat.getFreeClusterCount();

        FatChain chain = new FatChain(fs, 0);
        chain.allocateAndClear(4);
        assertCounterMatches(fs, "after allocateAndClear(4)");
        assertEquals(mountCount - 4, fat.getFreeClusterCount());

        chain.free(2);
        assertCounterMatches(fs, "after free(2)");
        assertEquals(mountCount - 2, fat.getFreeClusterCount());

        chain.freeAllClusters();
        assertCounterMatches(fs, "after freeAllClusters");
        assertEquals(mountCount, fat.getFreeClusterCount());

        FSDirectory root = fs.getRootEntry().getDirectory();
        int clusterSize = fs.getClusterSize();
        FSFile file = root.addFile("alloc.bin").getFile();
        byte[] data = new byte[clusterSize * 3];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();
        assertCounterMatches(fs, "after writing 3 clusters");
        assertEquals(mountCount - 3, fat.getFreeClusterCount());

        byte[] more = new byte[clusterSize * 2];
        for (int i = 0; i < more.length; i++) {
            more[i] = (byte) ((i + 1) & 0xFF);
        }
        file.write(file.getLength(), ByteBuffer.wrap(more));
        file.flush();
        fs.flush();
        assertCounterMatches(fs, "after growing by 2 clusters");
        assertEquals(mountCount - 5, fat.getFreeClusterCount());

        root.remove("alloc.bin");
        fs.flush();
        assertCounterMatches(fs, "after deleting grown file");
        assertEquals(mountCount, fat.getFreeClusterCount());

        for (int i = 0; i < 8; i++) {
            String name = "heavy" + i + ".bin";
            FSFile heavy = root.addFile(name).getFile();
            byte[] one = new byte[clusterSize];
            for (int j = 0; j < one.length; j++) {
                one[j] = (byte) ((j + i) & 0xFF);
            }
            heavy.write(0, ByteBuffer.wrap(one));
            heavy.flush();
            fs.flush();
            assertCounterMatches(fs, "after writing " + name);
            root.remove(name);
            fs.flush();
            assertCounterMatches(fs, "after deleting " + name);
            assertEquals(mountCount, fat.getFreeClusterCount());
        }
    }

    private void assertCounterMatches(FatFileSystem fs, String stage) throws Exception {
        Fat fat = fs.getFat();
        int scanned = fat.freeEntries();
        int counter = fat.getFreeClusterCount();
        assertEquals(stage + ": counter must equal freeEntries() scan", scanned, counter);
        fat.set(0, fat.get(0));
        assertEquals(stage + ": no-op set on used entry must not move counter", counter,
            fat.getFreeClusterCount());
        for (int i = 2; i < fat.size(); i++) {
            if (fat.get(i) == 0) {
                fat.set(i, 0);
                break;
            }
        }
        assertEquals(stage + ": no-op set on free entry must not move counter", counter,
            fat.getFreeClusterCount());
        assertEquals(stage + ": getFreeSpace must be counter * clusterSize", (long) counter
            * fs.getClusterSize(), fs.getFreeSpace());
        assertEquals(stage + ": getUsableSpace must equal getFreeSpace", fs.getFreeSpace(),
            fs.getUsableSpace());
    }

    private FatFileSystem mountRW(File image) throws Exception {
        device = new FileDevice(image, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, false);
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
