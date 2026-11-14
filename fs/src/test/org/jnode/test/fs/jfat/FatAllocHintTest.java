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
import java.lang.reflect.Field;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatCache;
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

/**
 * Bounds on the number of FAT entry accesses an allocation performs,
 * pinned with the cache access counter. The free-cluster hint is a lower
 * bound on where allocation starts scanning; on a nearly-full volume the
 * only free cluster can sit far below the hint, and every free transition
 * must lower the hint so the next allocation finds it without a wrap scan.
 */
public class FatAllocHintTest {

    private static final long MAX_ACCESSES_PER_SMALL_ALLOCATE = 60;
    private static final long MAX_ACCESSES_PER_PLENTIFUL_ALLOCATE = 120;

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
    public void testAllocateAfterFreeTransitionBelowStaleHintIsBounded() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat-alloc-hint");
        FatFileSystem fs = mountRW(tempImage);
        Fat fat = fs.getFat();
        int size = fat.size();
        int staleHint = size - 60;

        for (int i = fat.firstCluster(); i < size; i++) {
            if (fat.isFreeEntry(i)) {
                fat.set(i, fat.eofChain());
            }
        }
        fat.setLastFree(staleHint);
        fat.set(5, fat.freeEntry());
        assertEquals("free transition below the stale hint must lower the hint",
            5, fat.getLastFree());

        long before = fatAccessCount(fat);
        FatChain chain = new FatChain(fs, 0);
        chain.allocateAndClear(1);
        long accesses = fatAccessCount(fat) - before;

        assertFalse("the freed cluster below the stale hint must be allocatable",
            fat.isFreeEntry(5));
        assertTrue("allocation with the hint at the freed cluster must not scan the FAT, "
                + "but used " + accesses + " accesses",
            accesses <= MAX_ACCESSES_PER_SMALL_ALLOCATE);
    }

    @Test
    public void testAllocationsOnPlentifulVolumeAreBounded() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat-alloc-hint-plenty");
        FatFileSystem fs = mountRW(tempImage);
        Fat fat = fs.getFat();

        for (int i = 0; i < 8; i++) {
            long before = fatAccessCount(fat);
            FatChain chain = new FatChain(fs, 0);
            chain.allocateAndClear(2);
            long accesses = fatAccessCount(fat) - before;
            assertTrue("allocation " + i + " on a plentiful volume used " + accesses
                    + " FAT accesses",
                accesses <= MAX_ACCESSES_PER_PLENTIFUL_ALLOCATE);
        }
    }

    @Test
    public void testAllocatingLastFreeClusterDoesNotRescanFatForHint() throws Exception {
        tempImage = Fat12ImageBuilder.createImageFile("jfat-alloc-hint-last");
        FatFileSystem fs = mountRW(tempImage);
        Fat fat = fs.getFat();

        int free = fat.getFreeClusterCount();
        assertTrue("fixture must start with free clusters", free > 0);
        long before = fatAccessCount(fat);
        FatChain chain = new FatChain(fs, 0);
        chain.allocateAndClear(free);
        long accesses = fatAccessCount(fat) - before;

        assertEquals("allocation must consume every free cluster", 0,
            fat.getFreeClusterCount());
        assertTrue("consuming the last free cluster must not rescan the FAT to move the "
                + "hint, but used " + accesses + " accesses for " + free + " clusters",
            accesses <= (long) free * 5 + 200);
    }

    private FatFileSystem mountRW(File image) throws Exception {
        device = new FileDevice(image, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(device, false);
    }

    private static long fatAccessCount(Fat fat) throws Exception {
        Field f = Fat.class.getDeclaredField("cache");
        f.setAccessible(true);
        return ((FatCache) f.get(fat)).getAccess();
    }
}
