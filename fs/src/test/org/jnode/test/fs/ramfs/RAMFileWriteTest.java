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
import org.jnode.fs.ramfs.RAMFile;
import org.jnode.fs.ramfs.RAMFileSystem;
import org.jnode.fs.ramfs.RAMFileSystemType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RAMFileWriteTest {

    private RAMFileSystem fileSystem;
    private FSDirectory root;

    @Before
    public void setUp() throws Exception {
        fileSystem = new RAMFileSystem(null, false, 104857600, new RAMFileSystemType());
        root = fileSystem.getRootEntry();
    }

    @Test
    public void writePositionOffsetBufferExtendsByRemaining() throws IOException {
        FSEntry entry = root.addFile("test.bin");
        RAMFile file = (RAMFile) entry;

        byte[] first = new byte[] { 0, 1, 2, 3, 4 };
        file.write(0, ByteBuffer.wrap(first));

        byte[] bytes = new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 };
        ByteBuffer src = ByteBuffer.wrap(bytes, 3, 5);
        file.write(5, src);

        Assert.assertEquals(10, file.getLength());
        Assert.assertEquals(8, src.position());

        byte[] content = new byte[10];
        file.read(0, ByteBuffer.wrap(content));
        Assert.assertArrayEquals(new byte[] { 0, 1, 2, 3, 4, 13, 14, 15, 16, 17 }, content);
    }
}
