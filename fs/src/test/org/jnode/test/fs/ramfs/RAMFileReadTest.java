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

package org.jnode.test.fs.ramfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.ramfs.RAMDirectory;
import org.jnode.fs.ramfs.RAMFile;
import org.jnode.fs.ramfs.RAMFileSystem;
import org.jnode.fs.ramfs.RAMFileSystemType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RAMFileReadTest {

    private RAMFileSystem fileSystem;
    private RAMFile file;

    @Before
    public void setUp() throws Exception {
        fileSystem = new RAMFileSystem(null, false, 104857600, new RAMFileSystemType());
        FSDirectory root = fileSystem.getRootEntry();
        FSEntry entry = root.addFile("test.bin");
        file = (RAMFile) entry;
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 37 + 11) & 0xff);
        }
        file.write(0, ByteBuffer.wrap(data));
    }

    @Test
    public void readConsumesExactlyRemainingBytes() throws IOException {
        byte[] dst = new byte[32];
        java.util.Arrays.fill(dst, (byte) 0);
        ByteBuffer buf = ByteBuffer.wrap(dst, 2, 10);
        file.read(20, buf);
        Assert.assertEquals(12, buf.position());
        Assert.assertEquals((byte) ((11 + 20 * 37) & 0xff), dst[2]);
        for (int i = 3; i < 12; i++) {
            Assert.assertEquals((byte) ((11 + (20 + i - 2) * 37) & 0xff), dst[i]);
        }
        for (int i = 0; i < 2; i++) {
            Assert.assertEquals(0, dst[i]);
        }
        for (int i = 12; i < dst.length; i++) {
            Assert.assertEquals(0, dst[i]);
        }
    }

    @Test
    public void readWholeFileFromPositionZero() throws IOException {
        byte[] block = new byte[300];
        ByteBuffer buf = ByteBuffer.wrap(block);
        file.read(0, buf);
        Assert.assertEquals(300, buf.position());
        for (int i = 0; i < 300; i++) {
            Assert.assertEquals((byte) ((i * 37 + 11) & 0xff), block[i]);
        }
    }

    @Test
    public void readFromSliceBufferHonorsArrayOffsetAndPosition() throws IOException {
        byte[] backing = new byte[64];
        java.util.Arrays.fill(backing, (byte) 0x7f);
        ByteBuffer buf = ByteBuffer.wrap(backing, 3, 20).slice();
        buf.position(5);
        file.read(20, buf);
        Assert.assertEquals(20, buf.position());
        for (int i = 0; i < 8; i++) {
            Assert.assertEquals((byte) 0x7f, backing[i]);
        }
        for (int i = 8; i < 23; i++) {
            Assert.assertEquals((byte) ((11 + (20 + i - 8) * 37) & 0xff), backing[i]);
        }
        for (int i = 23; i < backing.length; i++) {
            Assert.assertEquals((byte) 0x7f, backing[i]);
        }
    }

    @Test
    public void readAtEndOfFile() throws IOException {
        byte[] dst = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(dst, 4, 12);
        file.read(300 - 12, buf);
        Assert.assertEquals(16, buf.position());
        Assert.assertEquals((byte) ((11 + 288 * 37) & 0xff), dst[4]);
        Assert.assertEquals((byte) ((11 + 299 * 37) & 0xff), dst[15]);
    }
}
