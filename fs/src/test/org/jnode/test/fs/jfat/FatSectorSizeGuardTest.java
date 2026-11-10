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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.jfat.FatFormatter;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.partitions.PartitionTableEntry;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The FAT32 formatter only supports 512-byte sectors: every structural offset
 * used to be hardcoded to 512-byte offsets while a non-512 sector size was only
 * logged, producing a volume the mount path could not read. The contract is now
 * enforced end-to-end with a fail-fast exception before any write reaches the
 * device. The mount path ({@code Fat.create}) enforces the same constraint and
 * fails with a {@code FileSystemException} before any read is issued.
 *
 * @see <a href="https://github.com/LSantha/jnode_ai/issues/888">ticket #888</a>
 * @see <a href="https://github.com/LSantha/jnode_ai/issues/902">ticket #902</a>
 */
public class FatSectorSizeGuardTest {

    private static final byte FILL_BYTE = (byte) 0xA5;

    private static class SectorSizeFileDevice extends FileDevice {
        private final int sectorSize;

        SectorSizeFileDevice(File file, String mode, int sectorSize) throws Exception {
            super(file, mode);
            this.sectorSize = sectorSize;
        }

        @Override
        public int getSectorSize() throws IOException {
            return sectorSize;
        }
    }

    private static class TrackingBlockDeviceAPI implements BlockDeviceAPI {
        private boolean wrote;

        public long getLength() {
            return 0;
        }

        public void read(long devOffset, ByteBuffer dest) {
        }

        public void write(long devOffset, ByteBuffer src) {
            wrote = true;
        }

        public void flush() {
        }

        boolean wrote() {
            return wrote;
        }
    }

    private static class TrackingFSBlockDeviceAPI implements FSBlockDeviceAPI {
        private final int sectorSize;
        private boolean read;

        TrackingFSBlockDeviceAPI(int sectorSize) {
            this.sectorSize = sectorSize;
        }

        public long getLength() {
            return 512L * 65536L;
        }

        public void read(long devOffset, ByteBuffer dest) {
            read = true;
        }

        public void write(long devOffset, ByteBuffer src) {
        }

        public void flush() {
        }

        public int getSectorSize() throws IOException {
            return sectorSize;
        }

        public PartitionTableEntry getPartitionTableEntry() {
            return null;
        }

        boolean readCalled() {
            return read;
        }
    }

    private FileSystemService fss;
    private File diskFile;
    private FileDevice device;

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
        diskFile = File.createTempFile("jfat-sector-size-guard", ".img");
        diskFile.deleteOnExit();
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
        if (fss != null) {
            try {
                InitialNaming.unbind(FileSystemService.class);
            } catch (Exception e) {
            }
            fss = null;
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private byte[] snapshot(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
            return bytes;
        } finally {
            raf.close();
        }
    }

    @Test
    public void testPublicFormatterRejectsNon512SectorSizeAndWritesNothing() throws Exception {
        byte[] expected = new byte[1024 * 1024];
        Arrays.fill(expected, FILL_BYTE);
        RandomAccessFile raf = new RandomAccessFile(diskFile, "rw");
        try {
            raf.write(expected);
        } finally {
            raf.close();
        }
        device = new SectorSizeFileDevice(diskFile, "rw", 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        try {
            formatter.format(device);
            fail("expected FileSystemException for a 1024-byte-sector device");
        } catch (FileSystemException expected2) {
            assertNotNull(expected2.getMessage());
            assertTrue("expected sector size in message: " + expected2.getMessage(),
                    expected2.getMessage().contains("sector size"));
        }
        device.close();
        device = null;
        assertTrue("device image must be byte-identical after a rejected format",
                Arrays.equals(expected, snapshot(diskFile)));
    }

    @Test
    public void testPublicFormatterAccepts512SectorSize() throws Exception {
        device = new SectorSizeFileDevice(diskFile, "rw", 512);
        device.setLength(65536L * 512L);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        org.jnode.fs.jfat.FatFileSystem fs = formatter.format(device);
        fs.close();
    }

    @Test
    public void testPublicFormatterRejects2048SectorSizeAndWritesNothing() throws Exception {
        byte[] expected = new byte[8192];
        Arrays.fill(expected, FILL_BYTE);
        RandomAccessFile raf = new RandomAccessFile(diskFile, "rw");
        try {
            raf.write(expected);
        } finally {
            raf.close();
        }
        device = new SectorSizeFileDevice(diskFile, "rw", 2048);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        try {
            formatter.format(device);
            fail("expected FileSystemException for a 2048-byte-sector device");
        } catch (FileSystemException expected2) {
            assertTrue("expected sector size in message: " + expected2.getMessage(),
                    expected2.getMessage().contains("sector size"));
        }
        device.close();
        device = null;
        assertTrue("device image must be byte-identical after a rejected format",
                Arrays.equals(expected, snapshot(diskFile)));
    }

    @Test
    public void testLowLevelFormatterRejectsNon512BeforeAnyWrite() throws Exception {
        TrackingBlockDeviceAPI tracking = new TrackingBlockDeviceAPI();
        try {
            FatFormatter.HDDFormatter(1024, 65536, ClusterSize._1Kb, 0, tracking);
            fail("expected IOException for a 1024-byte sector size");
        } catch (IOException expected) {
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
        assertFalse("no write may reach the device before the geometry guard", tracking.wrote());
        try {
            new FatFormatter(FatFormatter.HD_DESC, 1024, 65536, 63, 255, ClusterSize._1Kb, 0, tracking);
            fail("expected IOException for a 1024-byte sector size");
        } catch (IOException expected) {
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
        assertFalse("no write may reach the device before the geometry guard", tracking.wrote());
        try {
            new FatFormatter(FatFormatter.HD_DESC, 2048, 65536, 63, 255, ClusterSize._1Kb, 0, tracking);
            fail("expected IOException for a 2048-byte sector size");
        } catch (IOException expected) {
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
        assertFalse("no write may reach the device before the geometry guard", tracking.wrote());
    }

    @Test
    public void testFatCreateRejectsNon512SectorSizeBeforeAnyRead() throws Exception {
        TrackingFSBlockDeviceAPI tracking = new TrackingFSBlockDeviceAPI(1024);
        try {
            Fat.create(tracking);
            fail("expected FileSystemException for a 1024-byte-sector device");
        } catch (FileSystemException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
        assertFalse("no read may reach the device before the sector-size guard", tracking.readCalled());
        TrackingFSBlockDeviceAPI tracking2048 = new TrackingFSBlockDeviceAPI(2048);
        try {
            Fat.create(tracking2048);
            fail("expected FileSystemException for a 2048-byte-sector device");
        } catch (FileSystemException expected) {
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
        assertFalse("no read may reach the device before the sector-size guard", tracking2048.readCalled());
    }

    @Test
    public void testMountRejectsNon512SectorSizeDevice() throws Exception {
        device = new SectorSizeFileDevice(diskFile, "rw", 512);
        device.setLength(65536L * 512L);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        org.jnode.fs.jfat.FatFileSystem fs = formatter.format(device);
        fs.close();
        device.close();

        device = new SectorSizeFileDevice(diskFile, "r", 1024);
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        try {
            type.create(device, true);
            fail("expected FileSystemException for a 1024-byte-sector device");
        } catch (FileSystemException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("expected sector size in message: " + expected.getMessage(),
                    expected.getMessage().contains("sector size"));
        }
    }

    @Test
    public void testMountAccepts512SectorSizeDevice() throws Exception {
        device = new SectorSizeFileDevice(diskFile, "rw", 512);
        device.setLength(65536L * 512L);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        org.jnode.fs.jfat.FatFileSystem fs = formatter.format(device);
        fs.close();
        device.close();

        device = new SectorSizeFileDevice(diskFile, "r", 512);
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        org.jnode.fs.jfat.FatFileSystem mounted = type.create(device, true);
        assertNotNull(mounted.getBootSector());
        mounted.close();
    }

    @Test
    public void testLowLevelFormatterAccepts512ByteSectors() throws Exception {
        TrackingBlockDeviceAPI tracking = new TrackingBlockDeviceAPI();
        FatFormatter.HDDFormatter(512, 65536, ClusterSize._1Kb, 0, tracking);
        assertTrue("a 512-byte-sector format must write to the device", tracking.wrote());
        TrackingBlockDeviceAPI direct = new TrackingBlockDeviceAPI();
        new FatFormatter(FatFormatter.HD_DESC, 512, 65536, 63, 255, ClusterSize._1Kb, 0, direct);
        assertTrue("a 512-byte-sector format must write to the device", direct.wrote());
    }
}
