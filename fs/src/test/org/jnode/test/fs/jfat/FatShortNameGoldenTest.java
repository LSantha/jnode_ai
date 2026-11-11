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

/**
 * Behaviour-freezing tests for the short name generator (FatName / FatDirectory).
 * The expected names below were captured from the un-refactored implementation;
 * they must not change when the generation strategy changes.
 */
public class FatShortNameGoldenTest {

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

        diskFile = File.createTempFile("jfat-golden", ".img");
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

    @Test
    public void testGeneratedShortNamesMatchFrozenCorpus() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSDirectory rootDir = fs.getRootEntry().getDirectory();
            for (String[] pair : GOLDEN_FILES) {
                FSEntry entry = rootDir.addFile(pair[0]);
                assertEquals("short name for '" + pair[0] + "'", pair[1], ((FatEntry) entry).getShortName());
            }
            for (String[] pair : GOLDEN_DIRS) {
                FSEntry entry = rootDir.addDirectory(pair[0]);
                assertEquals("short name for directory '" + pair[0] + "'", pair[1], ((FatEntry) entry).getShortName());
            }
        } finally {
            fs.close();
        }
    }

    private static final String[][] GOLDEN_FILES = {
        {"This is a very long filename.txt", "THISIS~1.TXT"},
        {"\u6587\u4ef6\u540d\u6d4b\u8bd5.txt", "_____~1.TXT"},
        {"\u0444\u0430\u0439\u043b_\u0441_\u0434\u043b\u0438\u043d\u043d\u044b\u043c_\u0438\u043c\u0435\u043d\u0435\u043c.doc", "______~1.DOC"},
        {"\u540d\u524d\u306b\u65e5\u672c\u8a9e\u3092\u542b\u3080\u30d5\u30a1\u30a4\u30eb.pdf", "______~1.PDF"},
        {"Archivo_con_nombre_muy_largo_y_acentos_\u00e1\u00e9\u00ed\u00f3\u00fa.log", "ARCHIV~1.LOG"},
        {"MyDocument.TXT", "MYDOCU~1.TXT"},
        {"VeryLongFileNameThatExceeds8Dot3.txt", "VERYLO~1.TXT"},
        {"AnotherLongNameForTesting.doc", "ANOTHE~1.DOC"},
        {"ThisIsAVeryLongFilenameThatExceeds8Dot3Limit.txt", "THISIS~2.TXT"},
        {"ChecksumValidationTestFileWithLongName.dat", "CHECKS~1.DAT"},
        {"1234567890123.txt", "123456~1.TXT"},
        {"123456789012345678901234.txt", "123456~2.TXT"},
        {"12345678901234567890123456789012345.txt", "123456~3.TXT"},
        {"123456789012345678901234567890123456789012345.txt", "123456~4.TXT"},
        {"LONGNAME.TXT", "LONGNAME.TXT"},
        {"Long Name With Spaces.txt", "LONGNA~1.TXT"},
        {"MiXeD_CaSe_NaMe.TxT", "MIXED_~1.TXT"},
        {"Unicode_\u0422\u0435\u0441\u0442_\u6587\u4ef6.txt", "UNICOD~1.TXT"},
        {"file.name.with.many.dots.txt", "FILE~1.TXT"},
        {".hiddenfile", "HIDDEN~1"},
        {"file+name=test.txt", "FILE_N~1.TXT"},
        {"file;name,test.txt", "FILE_N~2.TXT"},
        {"file[name]test.txt", "FILE_N~3.TXT"},
        {"FILEONETEST.TXT", "FILEON~1.TXT"},
        {"filetwotest.txt", "FILETW~1.TXT"},
        {"FileThreeTest.TXT", "FILETH~1.TXT"},
        {"FileFourTest.txt", "FILEFO~1.TXT"},
        {"FILEFIVETEST.txt", "FILEFI~1.TXT"},
        {"filesixTest.TXT", "FILESI~1.TXT"},
        {"AAAAAAAAAAAAABBBBBBBBBBBBBCCCCCCCCCCCCC.txt", "AAAAAA~1.TXT"},
        {"File_With_Very_Long_Name_That_Requires_Multiple_Long_Entries.txt", "FILE_W~1.TXT"},
        {"ReconstructionTestWithManyComponentsInLongName.dat", "RECONS~1.DAT"},
        {"ComponentOneComponentTwoComponentThree.txt", "COMPON~1.TXT"},
        {"File_With_Long_Name_Number_0_For_Testing_Purposes.txt", "FILE_W~2.TXT"},
        {"File_With_Long_Name_Number_1_For_Testing_Purposes.txt", "FILE_W~3.TXT"},
        {"File_With_Long_Name_Number_2_For_Testing_Purposes.txt", "FILE_W~4.TXT"},
        {"File_With_Long_Name_Number_3_For_Testing_Purposes.txt", "FILE_W~5.TXT"},
        {"File_With_Long_Name_Number_4_For_Testing_Purposes.txt", "FILE_W~6.TXT"},
        {"File_With_Long_Name_Number_5_For_Testing_Purposes.txt", "FILE_W~7.TXT"},
        {"File_With_Long_Name_Number_6_For_Testing_Purposes.txt", "FILE_W~8.TXT"},
        {"File_With_Long_Name_Number_7_For_Testing_Purposes.txt", "FILE_W~9.TXT"},
        {"File_With_Long_Name_Number_8_For_Testing_Purposes.txt", "FILE_~10.TXT"},
        {"File_With_Long_Name_Number_9_For_Testing_Purposes.txt", "FILE_~11.TXT"},
        {"File_With_Long_Name_Number_10_For_Testing_Purposes.txt", "FILE_~12.TXT"},
        {"File_With_Long_Name_Number_11_For_Testing_Purposes.txt", "FILE_~13.TXT"},
        {"File_With_Long_Name_Number_12_For_Testing_Purposes.txt", "FILE_~14.TXT"},
        {"File_With_Long_Name_Number_13_For_Testing_Purposes.txt", "FILE_~15.TXT"},
        {"File_With_Long_Name_Number_14_For_Testing_Purposes.txt", "FILE_~16.TXT"},
        {"File_With_Long_Name_Number_15_For_Testing_Purposes.txt", "FILE_~17.TXT"},
        {"File_With_Long_Name_Number_16_For_Testing_Purposes.txt", "FILE_~18.TXT"},
        {"File_With_Long_Name_Number_17_For_Testing_Purposes.txt", "FILE_~19.TXT"},
        {"File_With_Long_Name_Number_18_For_Testing_Purposes.txt", "FILE_~20.TXT"},
        {"File_With_Long_Name_Number_19_For_Testing_Purposes.txt", "FILE_~21.TXT"},
        {"File_With_Long_Name_Number_20_For_Testing_Purposes.txt", "FILE_~22.TXT"},
        {"File_With_Long_Name_Number_21_For_Testing_Purposes.txt", "FILE_~23.TXT"},
        {"File_With_Long_Name_Number_22_For_Testing_Purposes.txt", "FILE_~24.TXT"},
        {"File_With_Long_Name_Number_23_For_Testing_Purposes.txt", "FILE_~25.TXT"},
        {"File_With_Long_Name_Number_24_For_Testing_Purposes.txt", "FILE_~26.TXT"},
        {"File_With_Long_Name_Number_25_For_Testing_Purposes.txt", "FILE_~27.TXT"},
        {"File_With_Long_Name_Number_26_For_Testing_Purposes.txt", "FILE_~28.TXT"},
        {"File_With_Long_Name_Number_27_For_Testing_Purposes.txt", "FILE_~29.TXT"},
        {"File_With_Long_Name_Number_28_For_Testing_Purposes.txt", "FILE_~30.TXT"},
        {"File_With_Long_Name_Number_29_For_Testing_Purposes.txt", "FILE_~31.TXT"},
        {"File_With_Long_Name_Number_30_For_Testing_Purposes.txt", "FILE_~32.TXT"},
        {"File_With_Long_Name_Number_31_For_Testing_Purposes.txt", "FILE_~33.TXT"},
        {"File_With_Long_Name_Number_32_For_Testing_Purposes.txt", "FILE_~34.TXT"},
        {"File_With_Long_Name_Number_33_For_Testing_Purposes.txt", "FILE_~35.TXT"},
        {"File_With_Long_Name_Number_34_For_Testing_Purposes.txt", "FILE_~36.TXT"},
        {"File_With_Long_Name_Number_35_For_Testing_Purposes.txt", "FILE_~37.TXT"},
        {"File_With_Long_Name_Number_36_For_Testing_Purposes.txt", "FILE_~38.TXT"},
        {"File_With_Long_Name_Number_37_For_Testing_Purposes.txt", "FILE_~39.TXT"},
        {"File_With_Long_Name_Number_38_For_Testing_Purposes.txt", "FILE_~40.TXT"},
        {"File_With_Long_Name_Number_39_For_Testing_Purposes.txt", "FILE_~41.TXT"},
        {"File_With_Long_Name_Number_40_For_Testing_Purposes.txt", "FILE_~42.TXT"},
        {"File_With_Long_Name_Number_41_For_Testing_Purposes.txt", "FILE_~43.TXT"},
        {"File_With_Long_Name_Number_42_For_Testing_Purposes.txt", "FILE_~44.TXT"},
        {"File_With_Long_Name_Number_43_For_Testing_Purposes.txt", "FILE_~45.TXT"},
        {"File_With_Long_Name_Number_44_For_Testing_Purposes.txt", "FILE_~46.TXT"},
        {"File_With_Long_Name_Number_45_For_Testing_Purposes.txt", "FILE_~47.TXT"},
        {"File_With_Long_Name_Number_46_For_Testing_Purposes.txt", "FILE_~48.TXT"},
        {"File_With_Long_Name_Number_47_For_Testing_Purposes.txt", "FILE_~49.TXT"},
        {"File_With_Long_Name_Number_48_For_Testing_Purposes.txt", "FILE_~50.TXT"},
        {"File_With_Long_Name_Number_49_For_Testing_Purposes.txt", "FILE_~51.TXT"},
        {"DamagedEntryTest_File_Number_0_With_Long_Name.txt", "DAMAGE~1.TXT"},
        {"DamagedEntryTest_File_Number_1_With_Long_Name.txt", "DAMAGE~2.TXT"},
        {"DamagedEntryTest_File_Number_2_With_Long_Name.txt", "DAMAGE~3.TXT"},
        {"DamagedEntryTest_File_Number_3_With_Long_Name.txt", "DAMAGE~4.TXT"},
        {"DamagedEntryTest_File_Number_4_With_Long_Name.txt", "DAMAGE~5.TXT"},
        {"DamagedEntryTest_File_Number_5_With_Long_Name.txt", "DAMAGE~6.TXT"},
        {"DamagedEntryTest_File_Number_6_With_Long_Name.txt", "DAMAGE~7.TXT"},
        {"DamagedEntryTest_File_Number_7_With_Long_Name.txt", "DAMAGE~8.TXT"},
        {"DamagedEntryTest_File_Number_8_With_Long_Name.txt", "DAMAGE~9.TXT"},
        {"DamagedEntryTest_File_Number_9_With_Long_Name.txt", "DAMAG~10.TXT"},
        {"Deep_File_With_Long_Name.txt", "DEEP_F~1.TXT"},
        {"AAAAAAAA..txt", "AAAAAA~2.TXT"},
        {"AAAAAAAA...txt", "AAAAAA~3.TXT"},
        {"AAAAAAAA....txt", "AAAAAA~4.TXT"},
        {"AAAAAAAA.....txt", "AAAAAA~5.TXT"},
        {"AAAAAAAA......txt", "AAAAAA~6.TXT"},
        {"AAAAAAAA.......txt", "AAAAAA~7.TXT"},
        {"AAAAAAAA........txt", "AAAAAA~8.TXT"},
        {"AAAAAAAA.........txt", "AAAAAA~9.TXT"},
        {"AAAAAAAA..........txt", "AAAAA~10.TXT"},
        {"AAAAAAAA...........txt", "AAAAA~11.TXT"},
        {"AAAAAAAA............txt", "AAAAA~12.TXT"},
        {"AAAAAAAA.............txt", "AAAAA~13.TXT"},
        {"AAAAAAAA..............txt", "AAAAA~14.TXT"},
        {"AAAAAAAA...............txt", "AAAAA~15.TXT"},
        {"no_extension_at_all_long_name", "NO_EXT~1"},
        {"trailingdot.", "TRAILI~1"},
        {"eightlet.txt", "eightlet.txt"},
        {"ninelettX.txt", "NINELE~1.TXT"},
        {"UPPERCASE.TXT", "UPPERC~1.TXT"},
        {"AAAAAAAA.txt", "AAAAAAAA.txt"},
    };

    private static final String[][] GOLDEN_DIRS = {
        {"Very Long Directory Name", "VERYLO~1"},
        {"Another_Long_Dir_Name_With_Unicode_\u6d4b\u8bd5", "ANOTHE~1"},
        {"Third Level Directory \u540d\u524d", "THIRDL~1"},
    };
}
