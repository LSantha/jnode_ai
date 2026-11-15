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
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.jnode.partitions.PartitionTableEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the write-cost contract of the FAT dirty-set flush
 * ({@code FatCache.flushDirty()}): a single FAT element dirtied by a
 * chain operation must produce exactly one device write per FAT copy,
 * not a write per cached element, and the explicit {@code fs.flush()}
 * boundary must still flush every dirty element.
 */
public class FatDirtyFlushTest {

    private File diskFile;
    private FileDevice fileDevice;
    private CountingFSBlockDeviceAPI countingApi;
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

        diskFile = File.createTempFile("jfat-dirtyflush-test", ".img");
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

    @Test
    public void testTruncateDirtiesOneFatElementWritesOnePerFatCopy() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            int nrFats = fs.getBootSector().getNrFats();
            FSFile file = fs.getRootEntry().getDirectory().addFile("trunc.bin").getFile();
            byte[] data = pattern(5000);
            file.write(0, ByteBuffer.wrap(data));
            file.flush();
            fs.flush();

            countingApi.reset();
            file.setLength(1024);

            assertEquals("truncate must perform exactly one device write per FAT copy",
                nrFats, countingApi.fatRegionWriteCount());
        } finally {
            closeQuietly(fs);
        }
    }

    @Test
    public void testFullFlushWritesEveryDirtyElement() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            int nrFats = fs.getBootSector().getNrFats();
            FSFile file = fs.getRootEntry().getDirectory().addFile("full.bin").getFile();
            file.write(0, ByteBuffer.wrap(pattern(2048)));
            file.flush();
            fs.flush();

            Fat fat = fs.getFat();
            int upper = (int) Math.min(300, (long) fat.size() - 1);
            assertTrue("FAT must span more than one 512-byte element", upper >= 256);
            fat.set(2, fat.get(2));
            fat.set(upper, fat.get(upper));

            countingApi.reset();
            fs.flush();

            assertEquals("fs.flush() must write every dirty FAT element, one write per FAT copy",
                2L * nrFats, countingApi.fatRegionWriteCount());
        } finally {
            closeQuietly(fs);
        }
    }

    @Test
    public void testCleanReadIssuesNoFatWrites() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        try {
            FSFile file = fs.getRootEntry().getDirectory().addFile("read.bin").getFile();
            byte[] data = pattern(2048);
            file.write(0, ByteBuffer.wrap(data));
            file.flush();
            fs.flush();

            countingApi.reset();
            ByteBuffer dst = ByteBuffer.allocate(512);
            file.read(0, dst);

            assertEquals("a clean read must not write the FAT region",
                0, countingApi.fatRegionWriteCount());
        } finally {
            closeQuietly(fs);
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        countingApi = new CountingFSBlockDeviceAPI(fileDevice);
        fileDevice.unregisterAPI(FSBlockDeviceAPI.class);
        fileDevice.unregisterAPI(BlockDeviceAPI.class);
        fileDevice.registerAPI(FSBlockDeviceAPI.class, countingApi);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        FatFileSystem fs = formatter.format(fileDevice);
        countingApi.setFatRegion(fatRegionStart(fs), fatRegionEnd(fs));
        countingApi.reset();
        return fs;
    }

    private static long fatRegionStart(FatFileSystem fs) {
        return fs.getFat().getFirstSector(0) * fs.getBootSector().getBytesPerSector();
    }

    private static long fatRegionEnd(FatFileSystem fs) {
        int nrFats = fs.getBootSector().getNrFats();
        return (fs.getFat().getLastSector(nrFats - 1) + 1)
            * fs.getBootSector().getBytesPerSector();
    }

    private static void closeQuietly(FatFileSystem fs) {
        try {
            fs.close();
        } catch (Exception e) {
        }
    }

    private static byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (0x5A + i * 31);
        }
        return data;
    }

    private static final class CountingFSBlockDeviceAPI implements FSBlockDeviceAPI {
        private final FileDevice delegate;
        private volatile long fatRegionStart;
        private volatile long fatRegionEnd;
        private final AtomicLong fatRegionWrites = new AtomicLong();

        private CountingFSBlockDeviceAPI(FileDevice delegate) {
            this.delegate = delegate;
        }

        public long getLength() throws IOException {
            return delegate.getLength();
        }

        public void read(long devOffset, ByteBuffer dest) throws IOException {
            delegate.read(devOffset, dest);
        }

        public void write(long devOffset, ByteBuffer src) throws IOException {
            int length = src.remaining();
            if (devOffset >= fatRegionStart && devOffset + length <= fatRegionEnd) {
                fatRegionWrites.incrementAndGet();
            }
            delegate.write(devOffset, src);
        }

        public void flush() throws IOException {
            delegate.flush();
        }

        public int getSectorSize() throws IOException {
            return delegate.getSectorSize();
        }

        public PartitionTableEntry getPartitionTableEntry() {
            return delegate.getPartitionTableEntry();
        }

        void setFatRegion(long fatRegionStart, long fatRegionEnd) {
            this.fatRegionStart = fatRegionStart;
            this.fatRegionEnd = fatRegionEnd;
        }

        long fatRegionWriteCount() {
            return fatRegionWrites.get();
        }

        void reset() {
            fatRegionWrites.set(0);
        }
    }
}
