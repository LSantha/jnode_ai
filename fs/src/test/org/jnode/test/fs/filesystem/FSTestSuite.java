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

package org.jnode.test.fs.filesystem;

import org.jnode.fs.jifs.files.JIFSFpluginJarTest;
import org.jnode.fs.service.def.FileHandleManagerTest;
import org.jnode.fs.service.def.FileHandleBarrierTest;
import org.jnode.test.fs.ext4.Ext4FileSystemTest;
import org.jnode.test.fs.filesystem.tests.BasicFSTest;
import org.jnode.test.fs.filesystem.tests.ConcurrentAccessFSTest;
import org.jnode.test.fs.filesystem.tests.FileFSTest;
import org.jnode.test.fs.filesystem.tests.TreeFSTest;
import org.jnode.test.fs.jfat.FatUtilsConcurrencyTest;
import org.jnode.test.fs.jfat.BootSectorValidationTest;
import org.jnode.test.fs.jfat.FatApiConsistencyTest;
import org.jnode.test.fs.jfat.Fat12ChainTest;
import org.jnode.test.fs.jfat.JfatDescriptorPermissionTest;
import org.jnode.test.fs.jfat.FatChainConcurrencyTest;
import org.jnode.test.fs.jfat.FatChainFreeWithCountTest;
import org.jnode.test.fs.jfat.FatChainReadHintTest;
import org.jnode.test.fs.jfat.FatChainShrinkHintTest;
import org.jnode.test.fs.jfat.FatDirectoryConcurrencyTest;
import org.jnode.test.fs.jfat.FatDirectoryExtensionTest;
import org.jnode.test.fs.jfat.FatDirectoryIndexTest;
import org.jnode.test.fs.jfat.FatCopyComparisonTest;
import org.jnode.test.fs.jfat.FatFileSystemTest;
import org.jnode.test.fs.jfat.FatFormatterRawStructureTest;
import org.jnode.test.fs.jfat.FatFormatterRoundTripTest;
import org.jnode.test.fs.jfat.FatFsInfoRuntimeTest;
import org.jnode.test.fs.jfat.FatFreeClusterCountTest;
import org.jnode.test.fs.jfat.FatSectorSizeGuardTest;
import org.jnode.test.fs.jfat.FatFailurePathTest;
import org.jnode.test.fs.jfat.FatFlushOrderTest;
import org.jnode.test.fs.jfat.FatBarrierTest;
import org.jnode.test.fs.jfat.FatBeyondEofWriteTest;
import org.jnode.test.fs.jfat.FatSetLengthTest;
import org.jnode.test.fs.jfat.FatReadWriteTest;
import org.jnode.test.fs.jfat.FatRootDirectoryWriteTest;
import org.jnode.test.fs.jfat.FatSetNameTest;
import org.jnode.test.fs.jfat.FatNumericTailTest;
import org.jnode.test.fs.jfat.FatShortNameGoldenTest;
import org.jnode.test.fs.jfat.FatShortNameScanCountTest;
import org.jnode.test.fs.jfat.FatSizeBoundaryTest;
import org.jnode.test.fs.jfat.FatSlackSpaceTest;
import org.jnode.test.fs.jfat.FatWriteBench;
import org.jnode.test.fs.jfat.FatWriteTest;
import org.jnode.test.fs.jfat.JFatLongFileNameTest;
import org.jnode.test.fs.jfat.MkfsFatFileSystemTest;
import org.jnode.test.fs.ntfs.NTFSFileSystemTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/**
 * This class runs a suite of functional tests on the JNode file system
 * implementation.  The tests are designed to be run from the JNode development
 * sandbox.  The JNode core project needs to be on the bootclasspath to avoid
 * classloader security problems.
 *
 * @author Fabien DUMINY
 * @author crawley@jnode.org
 */
@RunWith(Suite.class)
@SuiteClasses({
    BootSectorValidationTest.class,
    BasicFSTest.class,
    ConcurrentAccessFSTest.class,
    FileFSTest.class,
    TreeFSTest.class,
    Ext4FileSystemTest.class,
    NTFSFileSystemTest.class,
    FatReadWriteTest.class,
    FatWriteTest.class,
    FatSetNameTest.class,
    FatFailurePathTest.class,
    FatFlushOrderTest.class,
    FatBarrierTest.class,
    FatBeyondEofWriteTest.class,
    FatSetLengthTest.class,
    FatSizeBoundaryTest.class,
    FatDirectoryExtensionTest.class,
    FatDirectoryIndexTest.class,
    FatShortNameGoldenTest.class,
    FatShortNameScanCountTest.class,
    FatNumericTailTest.class,
    FatFileSystemTest.class,
    FatCopyComparisonTest.class,
    FatFormatterRoundTripTest.class,
    FatFormatterRawStructureTest.class,
    FatFreeClusterCountTest.class,
    FatFsInfoRuntimeTest.class,
    FatSectorSizeGuardTest.class,
    MkfsFatFileSystemTest.class,
    Fat12ChainTest.class,
    JfatDescriptorPermissionTest.class,
    FatChainConcurrencyTest.class,
    FatDirectoryConcurrencyTest.class,
    FatChainFreeWithCountTest.class,
    FatChainReadHintTest.class,
    FatChainShrinkHintTest.class,
    FatRootDirectoryWriteTest.class,
    FatSlackSpaceTest.class,
    FatWriteBench.class,
    JFatLongFileNameTest.class,
    FileHandleManagerTest.class,
    FileHandleBarrierTest.class,
    JIFSFpluginJarTest.class,
    FatApiConsistencyTest.class,
    FatUtilsConcurrencyTest.class,
}
)
public class FSTestSuite {

}

