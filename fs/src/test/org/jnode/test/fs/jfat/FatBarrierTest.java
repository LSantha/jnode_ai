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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Pins the durability barriers of the JFAT stack with a flush-recording
 * {@link FileDevice}: every durability point must reach
 * {@code BlockDeviceAPI.flush()}, which the IDE driver turns into a
 * FLUSH CACHE command.
 */
public class FatBarrierTest {

    private static final String FILE_NAME = "barrier.txt";

    private static class RecordingFileDevice extends FileDevice {
        private final List<Boolean> flushEvents = new ArrayList<Boolean>();

        private RecordingFileDevice(File file, String mode) throws Exception {
            super(file, mode);
        }

        @Override
        public void flush() {
            flushEvents.add(Boolean.TRUE);
        }

        private int flushCount() {
            int n = 0;
            for (Boolean event : flushEvents) {
                if (event != null) {
                    n++;
                }
            }
            return n;
        }

        private void clearFlushes() {
            flushEvents.clear();
        }
    }

    private File diskFile;
    private RecordingFileDevice device;
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
        DummyExtension extension = new DummyExtension();
        DummyConfigurationElement element = new DummyConfigurationElement();
        element.addAttribute("class", "org.jnode.fs.jfat.FatFileSystemType");
        extension.addElement(element);
        ep.addExtension(extension);
        fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-barrier-test", ".img");
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
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private FatFileSystem formatAndOpenRW() throws Exception {
        device = new RecordingFileDevice(diskFile, "rw");
        device.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        return formatter.format(device);
    }

    @Test
    public void testFileFlushIssuesDeviceBarrier() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        device.clearFlushes();
        file.flush();

        assertTrue("file.flush() must end in a device barrier", device.flushCount() >= 1);
        fs.close();
    }

    @Test
    public void testFileSystemFlushIssuesDeviceBarrier() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(new byte[]{5, 6, 7, 8}));

        device.clearFlushes();
        fs.flush();

        assertTrue("fs.flush() must end in a device barrier", device.flushCount() >= 1);
        fs.close();
    }

    @Test
    public void testCloseIssuesDeviceBarrier() throws Exception {
        FatFileSystem fs = formatAndOpenRW();
        FSFile file = fs.getRootEntry().getDirectory().addFile(FILE_NAME).getFile();
        file.write(0, ByteBuffer.wrap(new byte[]{9, 10, 11, 12}));

        device.clearFlushes();
        fs.close();

        assertTrue("fs.close() must end in a device barrier", device.flushCount() >= 1);
    }
}
