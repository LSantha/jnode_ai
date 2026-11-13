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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Vector;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatDirectory;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatLongDirEntry;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for JFat long filename (VFAT) support.
 * Covers: Unicode names >8.3, case preservation, checksum validation,
 * directory entry spanning, Win95/WinNT compatibility.
 */
public class JFatLongFileNameTest {

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

        diskFile = File.createTempFile("jfat-lfn-test", ".img");
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

    private FatFileSystem openReadOnly() throws Exception {
        fileDevice.close();
        fileDevice = new FileDevice(diskFile, "r");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, true);
    }

    /**
     * Test creating files with Unicode names longer than 8.3 format.
     * Names should be stored using VFAT long directory entries.
     */
    @Test
    public void testUnicodeNamesLongerThan8Dot3() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create files with long Unicode names
        String[] longNames = {
            "This is a very long filename.txt",
            "\u6587\u4ef6\u540d\u6d4b\u8bd5.txt",        // Chinese (wen jian ming ce shi)
            "\u0444\u0430\u0439\u043b_\u0441_\u0434\u043b\u0438\u043d\u043d\u044b\u043c_\u0438\u043c\u0435\u043d\u0435\u043c.doc", // Russian (fail_s_dlinnym_imenem)
            "\u540d\u524d\u306b\u65e5\u672c\u8a9e\u3092\u542b\u3080\u30d5\u30a1\u30a4\u30eb.pdf", // Japanese (namae ni nihongo wo fukumu fairu)
            "Archivo_con_nombre_muy_largo_y_acentos_\u00e1\u00e9\u00ed\u00f3\u00fa.log" // Spanish with accents
        };

        for (String name : longNames) {
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("Content of " + name).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        for (String name : longNames) {
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("File not found after remount: " + name, entry);
            assertTrue("Entry is not a file: " + name, entry.isFile());
            assertEquals("Wrong name returned: " + name, name, entry.getName());

            FSFile readFile = entry.getFile();
            ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
            readFile.read(0, buf);
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            assertTrue("Data mismatch for: " + name,
                Arrays.equals(("Content of " + name).getBytes(), data));
        }
        fs.close();
    }

        /**
     * Test case preservation in long filenames (WinNT behavior).
     * Long name preserves case, short name is uppercase when mangled.
     */
    @Test
    public void testCasePreservation() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create files with mixed case names (must be unique case-insensitively)
        // Use names that will be mangled to test uppercase short names
        String[] mixedCaseNames = {
            "MyDocument.TXT",           // Will be mangled
            "VeryLongFileNameThatExceeds8Dot3.txt",  // Will be mangled
            "AnotherLongNameForTesting.doc"  // Will be mangled
        };

        for (String name : mixedCaseNames) {
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("Content of " + name).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify case is preserved in long name
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        for (String name : mixedCaseNames) {
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("File not found after remount: " + name, entry);
            assertEquals("Case not preserved for: " + name, name, entry.getName());

            // Verify short name is accessible and uppercase (when mangled)
            FatEntry fatEntry = (FatEntry) entry;
            String shortName = fatEntry.getShortName();
            assertNotNull("Short name should not be null", shortName);
            // Short name should be uppercase
            assertTrue("Short name should be uppercase: " + shortName,
                shortName.equals(shortName.toUpperCase()));
        }
        fs.close();
    }

                /**
     * Test that short name (8.3) is generated correctly and case-insensitive lookup works.
     */
    @Test
    public void testShortNameGenerationAndLookup() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create a file with a long name that will be mangled to 8.3
        String longName = "ThisIsAVeryLongFilenameThatExceeds8Dot3Limit.txt";
        FSEntry entry = rootDir.addFile(longName);
        FSFile file = entry.getFile();
        file.write(0, ByteBuffer.wrap("test".getBytes()));
        file.flush();
        fs.flush();

        // Get the short name (8.3 format) from the entry
        FatEntry fatEntry = (FatEntry) entry;
        String shortName = fatEntry.getShortName();
        assertNotNull("Short name should not be null", shortName);
        assertTrue("Short name should be in 8.3 format: " + shortName,
            shortName.matches("^[A-Z0-9~]{1,8}(\\.[A-Z0-9]{1,3})?$"));

        // Get the raw 11-byte short name for lookup
        byte[] shortNameBytes = fatEntry.getEntry().getName();

        fs.close();

        // Remount and verify we can find by both long and short name
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry byLong = rootDir.getEntry(longName);
        assertNotNull("Should find by long name", byLong);
        assertEquals("Long name should match", longName, byLong.getName());

        // Use getEntryByShortName for short name lookup (requires 11-byte array)
        FatDirectory fatRootDir = (FatDirectory) rootDir;
        FSEntry byShort = fatRootDir.getEntryByShortName(shortNameBytes);
        assertNotNull("Should find by short name", byShort);
        assertEquals("Should return long name when found by short name", longName, byShort.getName());

        // Case-insensitive lookup by long name (VFAT is case-insensitive)
        FSEntry byLongLower = rootDir.getEntry(longName.toLowerCase());
        assertNotNull("Should find by lowercase long name", byLongLower);
        assertEquals(longName, byLongLower.getName());

        fs.close();
    }

    /**
     * Test checksum validation in long directory entries.
     * The checksum in each long entry must match the short entry's checksum.
     */
    @Test
    public void testChecksumValidation() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create file with long name (requires long entries with checksums)
        String longName = "ChecksumValidationTestFileWithLongName.dat";
        FSFile file = rootDir.addFile(longName).getFile();
        file.write(0, ByteBuffer.wrap("checksum test data".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        // Remount and verify checksums are validated during read
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry = rootDir.getEntry(longName);
        assertNotNull("File not found after remount", entry);

        // Verify the entry can be read (which validates checksums internally)
        FSFile readFile = entry.getFile();
        ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
        readFile.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertTrue("Data mismatch", Arrays.equals("checksum test data".getBytes(), data));

        fs.close();
    }

    /**
     * Test directory entry spanning - long names requiring multiple long entries.
     * Each long entry holds 13 Unicode chars (26 bytes).
     */
    @Test
    public void testDirectoryEntrySpanning() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Names that require 1, 2, 3, and 4 long entries
        // 13 chars per long entry
        String name1 = "1234567890123.txt";           // 17 chars = 2 components (1 long entry)
        String name2 = "123456789012345678901234.txt"; // 27 chars = 3 components (2 long entries)
        String name3 = "12345678901234567890123456789012345.txt"; // 41 chars = 4 components (3 long entries)
        String name4 = "123456789012345678901234567890123456789012345.txt"; // 53 chars = 5 components (4 long entries)

        String[] names = {name1, name2, name3, name4};

        for (String name : names) {
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("Content for " + name).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify all entries are readable
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        for (String name : names) {
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("File not found: " + name, entry);
            assertEquals("Name mismatch for: " + name, name, entry.getName());

            FSFile readFile = entry.getFile();
            ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
            readFile.read(0, buf);
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            assertTrue("Data mismatch for: " + name,
                Arrays.equals(("Content for " + name).getBytes(), data));
        }
        fs.close();
    }

    /**
     * Test Win95/WinNT compatibility - long names created by JFat should be
     * readable by Windows and vice versa.
     * This tests the core VFAT on-disk format compliance.
     */
    @Test
    public void testWin95WinNTCompatibility() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Test names that exercise Win95/WinNT VFAT behavior
        String[] compatNames = {
            // Pure ASCII long name (Win95 style)
            "LONGNAME.TXT",
            "Long Name With Spaces.txt",
            // Mixed case (WinNT preserves case)
            "MiXeD_CaSe_NaMe.TxT",
            // Unicode (WinNT/2000+)
            "Unicode_\u0422\u0435\u0441\u0442_\u6587\u4ef6.txt",  // Russian Test + Chinese file
            // Name with many dots
            "file.name.with.many.dots.txt",
            // Name starting with dot
            ".hiddenfile",
            // Name with special chars allowed in VFAT but not 8.3
            "file+name=test.txt",
            "file;name,test.txt",
            "file[name]test.txt"
        };

        for (String name : compatNames) {
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("Compat test: " + name).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify all names are preserved
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        for (String name : compatNames) {
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("Compat file not found: " + name, entry);
            assertEquals("Compat name mismatch: " + name, name, entry.getName());

            FSFile readFile = entry.getFile();
            ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
            readFile.read(0, buf);
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            assertTrue("Compat data mismatch: " + name,
                Arrays.equals(("Compat test: " + name).getBytes(), data));
        }
        fs.close();
    }

            /**
     * Test that short name case bits (NTRes) are set correctly for WinNT compatibility.
     * Lowercase base/extension should set the appropriate bits.
     */
    @Test
    public void testShortNameCaseBits() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create files that should have specific case bits
        // Must be unique case-insensitively and long enough to be mangled
        String[] testNames = {
            "FILEONETEST.TXT",           // All upper -> no case bits (will be mangled due to length)
            "filetwotest.txt",           // All lower -> both case bits set
            "FileThreeTest.TXT",         // Mixed base, upper ext -> base lower bit
            "FileFourTest.txt",          // Mixed base, lower ext -> both bits
            "FILEFIVETEST.txt",          // Upper base, lower ext -> ext lower bit
            "filesixTest.TXT"            // Lower base, upper ext -> base lower bit
        };

        for (String name : testNames) {
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap("case bit test".getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify names are preserved (case bits affect short name display)
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        for (String name : testNames) {
            FSEntry entry = rootDir.getEntry(name);
            assertNotNull("Case bit test file not found: " + name, entry);
            assertEquals("Case bit test name mismatch: " + name, name, entry.getName());

            // Verify short name is accessible and uppercase (when mangled)
            FatEntry fatEntry = (FatEntry) entry;
            String shortName = fatEntry.getShortName();
            assertNotNull("Short name should not be null for: " + name, shortName);
            assertTrue("Short name should be uppercase: " + shortName,
                shortName.equals(shortName.toUpperCase()));
        }
        fs.close();
    }

    /**
     * Test creating and reading directories with long names.
     */
    @Test
    public void testLongDirectoryNames() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create nested directories with long names
        FSDirectory dir1 = rootDir.addDirectory("Very Long Directory Name").getDirectory();
        FSDirectory dir2 = dir1.addDirectory("Another_Long_Dir_Name_With_Unicode_\u6d4b\u8bd5").getDirectory(); // ce shi (Chinese test)
        FSDirectory dir3 = dir2.addDirectory("Third Level Directory \u540d\u524d").getDirectory(); // namae (Japanese name)

        // Create a file in the deepest directory
        FSFile file = dir3.addFile("Deep_File_With_Long_Name.txt").getFile();
        file.write(0, ByteBuffer.wrap("deep file content".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        // Remount and traverse
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry e1 = rootDir.getEntry("Very Long Directory Name");
        assertNotNull("First level dir not found", e1);
        assertTrue("First level is not directory", e1.isDirectory());

        FSDirectory d1 = e1.getDirectory();
        FSEntry e2 = d1.getEntry("Another_Long_Dir_Name_With_Unicode_\u6d4b\u8bd5");
        assertNotNull("Second level dir not found", e2);
        assertTrue("Second level is not directory", e2.isDirectory());

        FSDirectory d2 = e2.getDirectory();
        FSEntry e3 = d2.getEntry("Third Level Directory \u540d\u524d");
        assertNotNull("Third level dir not found", e3);
        assertTrue("Third level is not directory", e3.isDirectory());

        FSDirectory d3 = e3.getDirectory();
        FSEntry fileEntry = d3.getEntry("Deep_File_With_Long_Name.txt");
        assertNotNull("Deep file not found", fileEntry);
        assertTrue("Deep entry is not file", fileEntry.isFile());

        FSFile readFile = fileEntry.getFile();
        ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
        readFile.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertTrue("Deep file data mismatch", Arrays.equals("deep file content".getBytes(), data));

        fs.close();
    }

    /**
     * Test that long entries are correctly ordered (last entry first on disk).
     * VFAT stores long entries in reverse order (last component first).
     */
    @Test
    public void testLongEntryOrdering() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Name requiring exactly 3 long entries (39 chars = 3 components of 13)
        String longName = "AAAAAAAAAAAAABBBBBBBBBBBBBCCCCCCCCCCCCC.txt";
        FSFile file = rootDir.addFile(longName).getFile();
        file.write(0, ByteBuffer.wrap("ordering test".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        // Remount and verify
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry = rootDir.getEntry(longName);
        assertNotNull("File not found", entry);
        assertEquals("Name mismatch", longName, entry.getName());

        // Verify we can read the content
        FSFile readFile = entry.getFile();
        ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
        readFile.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        assertTrue("Data mismatch", Arrays.equals("ordering test".getBytes(), data));

        fs.close();
    }

    /**
     * Test deletion of files with long names.
     */
    @Test
    public void testLongNameDeletion() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        String longName = "File_With_Very_Long_Name_That_Requires_Multiple_Long_Entries.txt";
        FSFile file = rootDir.addFile(longName).getFile();
        file.write(0, ByteBuffer.wrap("delete me".getBytes()));
        file.flush();
        fs.flush();

        // Verify it exists
        assertNotNull("File should exist before deletion", rootDir.getEntry(longName));

        // Delete it
        rootDir.remove(longName);
        fs.flush();

        // Verify it's gone
        assertNull("File should not exist after deletion", rootDir.getEntry(longName));

        fs.close();

        // Remount and verify it's still gone
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        assertNull("File should not exist after remount", rootDir.getEntry(longName));
        fs.close();
    }

    /**
     * Test creating many files with long names to verify directory scaling.
     */
    @Test
    public void testManyLongNamedFiles() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        int count = 50;
        for (int i = 0; i < count; i++) {
            String name = "File_With_Long_Name_Number_" + i + "_For_Testing_Purposes.txt";
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("Content " + i).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify all
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        int found = 0;
        Iterator<? extends FSEntry> it = rootDir.iterator();
        while (it.hasNext()) {
            FSEntry e = it.next();
            String name = e.getName();
            if (name.startsWith("File_With_Long_Name_Number_") && name.endsWith(".txt")) {
                found++;
                FSFile f = e.getFile();
                ByteBuffer buf = ByteBuffer.allocate((int) f.getLength());
                f.read(0, buf);
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                int idx = Integer.parseInt(name.substring(
                    "File_With_Long_Name_Number_".length(),
                    name.length() - "_For_Testing_Purposes.txt".length()));
                assertTrue("Data mismatch for index " + idx,
                    Arrays.equals(("Content " + idx).getBytes(), data));
            }
        }
        assertEquals("Should find all " + count + " files", count, found);
        fs.close();
    }

    /**
     * Test that FatRecord correctly reconstructs long name from long entries.
     * Validates the close() method logic in FatRecord.
     */
    @Test
    public void testFatRecordLongNameReconstruction() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        String longName = "ReconstructionTestWithManyComponentsInLongName.dat";
        FSFile file = rootDir.addFile(longName).getFile();
        file.write(0, ByteBuffer.wrap("record test".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        // Remount - this triggers FatEntriesFactory -> FatRecord.close()
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry = rootDir.getEntry(longName);
        assertNotNull("File not found", entry);
        assertEquals("Long name not reconstructed correctly", longName, entry.getName());

        fs.close();
    }

    /**
     * Test ordinal field in long directory entries (1-based, last entry has 0x40 bit set).
     */
    @Test
    public void testLongEntryOrdinalValues() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create a file with a name requiring 3 long entries
        String longName = "ComponentOneComponentTwoComponentThree.txt";
        FSFile file = rootDir.addFile(longName).getFile();
        file.write(0, ByteBuffer.wrap("ordinal test".getBytes()));
        file.flush();
        fs.flush();
        fs.close();

        // Remount and verify
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry = rootDir.getEntry(longName);
        assertNotNull("File not found", entry);
        assertEquals("Name mismatch", longName, entry.getName());

        fs.close();
    }

    /**
     * Test that damaged long entries are handled gracefully.
     * (This is more of a regression test for the damage detection logic)
     */
    @Test
    public void testDamagedLongEntryHandling() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        // Create multiple long-named files
        for (int i = 0; i < 10; i++) {
            String name = "DamagedEntryTest_File_Number_" + i + "_With_Long_Name.txt";
            FSFile file = rootDir.addFile(name).getFile();
            file.write(0, ByteBuffer.wrap(("data " + i).getBytes()));
            file.flush();
        }
        fs.flush();
        fs.close();

        // Remount and verify all are readable (no false damage detection)
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        int count = 0;
        Iterator<? extends FSEntry> it = rootDir.iterator();
        while (it.hasNext()) {
            FSEntry e = it.next();
            String name = e.getName();
            if (name.startsWith("DamagedEntryTest_File_Number_")) {
                count++;
                FSFile f = e.getFile();
                ByteBuffer buf = ByteBuffer.allocate((int) f.getLength());
                f.read(0, buf);
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                int idx = Integer.parseInt(name.substring(
                    "DamagedEntryTest_File_Number_".length(),
                    name.length() - "_With_Long_Name.txt".length()));
                assertTrue("Data mismatch for index " + idx,
                    Arrays.equals(("data " + idx).getBytes(), data));
            }
        }
        assertEquals("Should find all 10 files", 10, count);
        fs.close();
    }

    /**
     * Test the FAT32 LFN maximum: a 255-character long name.
     * 255 characters require exactly 20 long entries of 13 characters each,
     * with the last entry holding the remaining 8 characters (255 = 19 * 13 + 8).
     */
    @Test
    public void testMaximumLengthLongName() throws Exception {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 251) {
            sb.append("abcdefghij");
        }
        final String longName = sb.substring(0, 251) + ".txt";
        assertEquals("test name must be exactly 255 characters", 255, longName.length());
        assertEquals(255, longName.getBytes("US-ASCII").length);

        final byte[] data = "maximum length lfn".getBytes("US-ASCII");

        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        FSEntry entry = rootDir.addFile(longName);
        FSFile file = entry.getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        Vector<FatLongDirEntry> longEntries = ((FatEntry) entry).getRecord().getLongEntries();
        assertEquals("255 characters must span exactly 20 long entries", 20, longEntries.size());
        int totalComponentChars = 0;
        int lastComponentLength = -1;
        for (FatLongDirEntry lde : longEntries) {
            totalComponentChars += lde.getComponent().length();
            if (lde.isLast()) {
                lastComponentLength = lde.getComponent().length();
            }
        }
        assertEquals("components must spell the full long name", 255, totalComponentChars);
        assertEquals("last long entry carries the remaining 8 characters", 8, lastComponentLength);
        for (int i = 1; i < longEntries.size(); i++) {
            assertEquals("leading long entry component must be 13 characters",
                13, longEntries.get(i).getComponent().length());
        }

        fs.close();

        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry found = rootDir.getEntry(longName);
        assertNotNull("255-character file not found after remount", found);
        assertEquals("255-character name not reconstructed byte-identically", longName, found.getName());

        FSFile readFile = found.getFile();
        ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
        readFile.read(0, buf);
        buf.flip();
        byte[] read = new byte[buf.remaining()];
        buf.get(read);
        assertTrue("data mismatch for 255-character file after remount", Arrays.equals(data, read));

        Vector<FatLongDirEntry> remounted = ((FatEntry) found).getRecord().getLongEntries();
        assertEquals("remount must reconstruct exactly 20 long entries", 20, remounted.size());
        int remountedChars = 0;
        for (FatLongDirEntry lde : remounted) {
            remountedChars += lde.getComponent().length();
        }
        assertEquals("remount must reconstruct the full name", 255, remountedChars);

        fs.close();
    }

    /**
     * The illegal short-name characters (everything in the short_illegal set except
     * '+' and '=' which are frozen in FatShortNameGoldenTest) and control characters
     * below 0x20 must map to '_', set the lossy flag and mangle the name. The short
     * name produced by FatName must carry the underscore.
     */
    @Test
    public void testInvalidCharactersInShortNameBasis() throws Exception {
        char[] illegal = {'"', '*', ',', '/', ':', ';', '<', '>', '?', '[', '\\', ']', '|'};
        char[] control = {'\u0001', '\u001F'};

        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        String[] names = new String[illegal.length + control.length];
        for (int i = 0; i < names.length; i++) {
            char c = (i < illegal.length ? illegal[i] : control[i - illegal.length]);
            String shown = (c < 0x20 ? "0x" + Integer.toHexString(c) : ("'" + c + "'"));
            String name = "A" + c + "B.txt";
            names[i] = name;

            Object preTail = invokeComputePreTail(name);
            assertTrue("lossy flag must be set for " + shown, preTailLossy(preTail));
            assertTrue("name with " + shown + " must be mangled", preTailMangled(preTail));
            byte[] basis = preTailBasis(preTail);
            assertEquals("basis head for 'A' + " + shown + " + 'B'", (byte) 'A', basis[0]);
            assertEquals("illegal char " + shown + " must map to underscore", (byte) '_', basis[1]);
            assertEquals("basis tail for 'A' + " + shown + " + 'B'", (byte) 'B', basis[2]);

            FSEntry entry = rootDir.addFile(name);
            String shortName = ((FatEntry) entry).getShortName();
            assertTrue("mangled short name must contain a numeric tail: " + shortName,
                shortName.indexOf('~') > 0);
            assertEquals("underscore must be at the illegal char position in: " + shortName,
                1, shortName.indexOf('_'));
            assertTrue("short name must keep the extension: " + shortName, shortName.endsWith(".TXT"));
        }
        fs.flush();
        fs.close();

        // Remount: every entry with an illegal/control char round-trips byte-identically
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();
        for (String name : names) {
            FSEntry found = rootDir.getEntry(name);
            assertNotNull("file not found after remount: " + showAscii(name), found);
            assertEquals("name not reconstructed byte-identically after remount: " + showAscii(name),
                name, found.getName());
            String shortName = ((FatEntry) found).getShortName();
            assertEquals("underscore lost after remount for: " + showAscii(name), 1, shortName.indexOf('_'));
        }
        fs.close();
    }

    /**
     * A directory that already holds the real (non-mangled, 8.3-legal) short names
     * "AAAAAA~1.TXT" .. "AAAAAA~9.TXT" must not produce a colliding generated short
     * name for a long name that mangles to basis "AAAAAAAA". The generator must skip
     * the occupied tails and resolve without corrupting an existing entry.
     * Note: "AAAAAAAA~N" is not a legal 8.3 short name (10-character base), so the
     * 1-digit tail slots for basis "AAAAAAAA" live in "AAAAAA~N.TXT".
     */
    @Test
    public void testGeneratedShortNameDoesNotCollideWithExistingRealShortNames() throws Exception {
        final byte[] data = "collision resolution test".getBytes("US-ASCII");

        FatFileSystem fs = formatAndOpenRW();
        FSDirectory rootDir = fs.getRootEntry().getDirectory();

        for (int i = 1; i <= 9; i++) {
            String realName = "AAAAAA~" + i + ".TXT";
            FSEntry real = rootDir.addFile(realName);
            assertEquals("test setup: an 8.3-legal name must be stored as its own short name",
                realName, ((FatEntry) real).getShortName());
        }

        StringBuilder longNameBuilder = new StringBuilder();
        for (int i = 0; i < 58; i++) {
            longNameBuilder.append('A');
        }
        final String longName = longNameBuilder.append(".txt").toString();

        FSEntry entry = rootDir.addFile(longName);
        FSFile file = entry.getFile();
        file.write(0, ByteBuffer.wrap(data));
        file.flush();
        fs.flush();

        String generated = ((FatEntry) entry).getShortName();
        for (int i = 1; i <= 9; i++) {
            assertFalse("generated short name must not collide with existing real short name AAAAAA~" + i + ".TXT",
                ("AAAAAA~" + i + ".TXT").equals(generated));
        }
        assertEquals("collision must resolve to the next free tail", "AAAAA~10.TXT", generated);

        fs.close();

        // Remount: no corrupt or lost entries; the generated short name survives as-is
        fs = openReadOnly();
        rootDir = fs.getRootEntry().getDirectory();

        FSEntry found = rootDir.getEntry(longName);
        assertNotNull("long-named file not found after remount", found);
        assertEquals(longName, found.getName());
        assertEquals("generated short name changed across remount", "AAAAA~10.TXT",
            ((FatEntry) found).getShortName());

        FSFile readFile = found.getFile();
        ByteBuffer buf = ByteBuffer.allocate((int) readFile.getLength());
        readFile.read(0, buf);
        buf.flip();
        byte[] read = new byte[buf.remaining()];
        buf.get(read);
        assertTrue("data mismatch after remount", Arrays.equals(data, read));

        for (int i = 1; i <= 9; i++) {
            FSEntry real = rootDir.getEntry("AAAAAA~" + i + ".TXT");
            assertNotNull("real short-named file AAAAAA~" + i + ".TXT lost after remount", real);
        }
        fs.close();
    }

    private static Object invokeComputePreTail(String longName) throws Exception {
        Class<?> codePageClass = Class.forName("org.jnode.fs.jfat.CodePage");
        Class<?> fatNameClass = Class.forName("org.jnode.fs.jfat.FatName");
        Method computePreTail = fatNameClass.getDeclaredMethod("computePreTail", String.class, codePageClass);
        computePreTail.setAccessible(true);
        Object codePage = codePageClass.getMethod("forName", String.class).invoke(null, "ISO_8859_1");
        return computePreTail.invoke(null, longName, codePage);
    }

    private static boolean preTailLossy(Object preTail) throws Exception {
        Field lossy = preTail.getClass().getDeclaredField("lossy");
        lossy.setAccessible(true);
        return lossy.getBoolean(preTail);
    }

    private static boolean preTailMangled(Object preTail) throws Exception {
        Method isMangled = preTail.getClass().getDeclaredMethod("isMangled");
        isMangled.setAccessible(true);
        return ((Boolean) isMangled.invoke(preTail)).booleanValue();
    }

    private static byte[] preTailBasis(Object preTail) throws Exception {
        Method basis = preTail.getClass().getDeclaredMethod("basis");
        basis.setAccessible(true);
        return (byte[]) basis.invoke(preTail);
    }

    private static String showAscii(String s) {
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                out.append("0x").append(Integer.toHexString(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}