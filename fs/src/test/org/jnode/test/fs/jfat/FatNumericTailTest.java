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
import java.lang.reflect.Method;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatNumericTailTest {

    private File diskFile;
    private FileDevice fileDevice;
    private FileSystemService fss;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }

        DummyPluginDescriptor desc = new DummyPluginDescriptor(true);
        DummyExtensionPoint ep = new DummyExtensionPoint("types", "org.jnode.fs.types", "types");
        desc.addExtensionPoint(ep);
        for (String className : new String[]{
            "org.jnode.fs.jfat.FatFileSystemType",
            "org.jnode.fs.ext2.Ext2FileSystemType",
            "org.jnode.fs.fat.FatFileSystemType",
            "org.jnode.fs.exfat.ExFatFileSystemType",
            "org.jnode.fs.iso9660.ISO9660FileSystemType",
            "org.jnode.fs.hfsplus.HfsPlusFileSystemType",
            "org.jnode.fs.ntfs.NTFSFileSystemType",
            "org.jnode.fs.jifs.JifsFileSystemType"
        }) {
            DummyExtension extension = new DummyExtension();
            DummyConfigurationElement element = new DummyConfigurationElement();
            element.addAttribute("class", className);
            extension.addElement(element);
            ep.addExtension(extension);
        }
        fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-numtail", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        if (fileDevice != null) {
            fileDevice.close();
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(fileDevice);
    }

    private static String stemName(String stem, int plusCount) {
        StringBuilder sb = new StringBuilder(stem).append(".tx");
        for (int i = 0; i < plusCount; i++)
            sb.append('+');
        return sb.toString();
    }

    private String addAndGetShortName(FSDirectory dir, String longName) throws Exception {
        FSEntry entry = dir.addFile(longName);
        assertNotNull(entry);
        return ((FatEntry) entry).getShortName();
    }

    private String renameAndGetShortName(FSDirectory dir, String from, String to) throws Exception {
        FatEntry entry = (FatEntry) dir.getEntry(from);
        assertNotNull(entry);
        entry.setName(to);
        return entry.getShortName();
    }

    @Test
    public void testNumericTailPlacementAlwaysInsideNameField() throws Exception {
        Class<?> fatNameClass = Class.forName("org.jnode.fs.jfat.FatName");
        Method tailStart = fatNameClass.getDeclaredMethod("tailStart", int.class, int.class);
        tailStart.setAccessible(true);
        for (int p = -1; p <= 7; p++) {
            for (int tailLength = 1; tailLength <= 6; tailLength++) {
                Object o = tailStart.invoke(null, Integer.valueOf(p), Integer.valueOf(tailLength));
                int l = ((Integer) o).intValue();
                assertEquals("l for p=" + p + " L=" + tailLength,
                    Math.min(p + 1, 7 - tailLength), l);
                assertTrue("tail must fit inside the 8-byte name field for p=" + p + " L=" + tailLength,
                    l + tailLength <= 7);
            }
        }
    }

    @Test
    public void testSevenCharStemKeepsTailDigitsOutOfExtension() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            assertEquals("AAAAAA~1.TX_", addAndGetShortName(rootDir, stemName("AAAAAAA", 1)));
            assertEquals("AAAAAA~2.TX_", addAndGetShortName(rootDir, stemName("AAAAAAA", 2)));
            assertEquals("AAAAAA~3.TX_", addAndGetShortName(rootDir, stemName("AAAAAAA", 3)));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testSixCharStemTwoDigitTailStaysInNameField() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 1; i <= 9; i++)
                addAndGetShortName(rootDir, stemName("BBBBBB", i));
            assertEquals("BBBBB~10.TX_", addAndGetShortName(rootDir, stemName("BBBBBB", 10)));
            assertEquals("BBBBB~11.TX_", addAndGetShortName(rootDir, stemName("BBBBBB", 11)));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testFiveCharStemThreeDigitTailStaysInNameField() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 1; i <= 99; i++)
                addAndGetShortName(rootDir, stemName("CCCCC", i));
            assertEquals("CCCC~100.TX_", addAndGetShortName(rootDir, stemName("CCCCC", 100)));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testDeletedTailIsReissuedLowestFirst() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            assertEquals("DDDDDD~1.TX_", addAndGetShortName(rootDir, stemName("DDDDDD", 1)));
            assertEquals("DDDDDD~2.TX_", addAndGetShortName(rootDir, stemName("DDDDDD", 2)));
            assertEquals("DDDDDD~3.TX_", addAndGetShortName(rootDir, stemName("DDDDDD", 3)));

            rootDir.remove(stemName("DDDDDD", 2));

            assertEquals("DDDDDD~2.TX_", addAndGetShortName(rootDir, stemName("DDDDDD", 4)));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testTailReleasedByRenameIsReused() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            addAndGetShortName(rootDir, stemName("RRRRRR", 1));
            assertEquals("RRRRRR~2.TX_", addAndGetShortName(rootDir, stemName("RRRRRR", 2)));
            addAndGetShortName(rootDir, stemName("RRRRRR", 3));

            renameAndGetShortName(rootDir, stemName("RRRRRR", 1), stemName("SSSSSS", 1));

            assertEquals("RRRRRR~1.TX_", addAndGetShortName(rootDir, stemName("RRRRRR", 4)));
        } finally {
            fs.close();
        }
    }

    @Test
    public void testNextFreshKeepsLowestFreeTailAfterManyDeletes() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (int i = 1; i <= 6; i++)
                addAndGetShortName(rootDir, stemName("EEEEEE", i));

            rootDir.remove(stemName("EEEEEE", 2));
            rootDir.remove(stemName("EEEEEE", 4));
            rootDir.remove(stemName("EEEEEE", 6));

            assertEquals("EEEEEE~2.TX_", addAndGetShortName(rootDir, stemName("EEEEEE", 7)));
            assertEquals("EEEEEE~4.TX_", addAndGetShortName(rootDir, stemName("EEEEEE", 8)));
            assertEquals("EEEEEE~6.TX_", addAndGetShortName(rootDir, stemName("EEEEEE", 9)));
            assertEquals("EEEEEE~7.TX_", addAndGetShortName(rootDir, stemName("EEEEEE", 10)));
        } finally {
            fs.close();
        }
    }
}
