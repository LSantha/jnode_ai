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

package org.jnode.fs.jifs.files;

import java.nio.ByteBuffer;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class JIFSFpluginJarTest {

    @Test
    public void testReadDoesNotChangeSourceBuffer() {
        final byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        final ByteBuffer source = ByteBuffer.wrap(data);
        final JIFSFpluginJar jar = new TestJar(source);

        final ByteBuffer dest = ByteBuffer.allocate(256);
        jar.read(100, dest);

        final byte[] expected = new byte[256];
        System.arraycopy(data, 100, expected, 0, expected.length);
        assertArrayEquals(expected, dest.array());
        assertEquals(0, source.position());
        assertEquals(data.length, source.limit());
        assertEquals(data.length, jar.getLength());
        assertEquals(0, source.position());
    }

    @Test
    public void testReadFromDirectBuffer() {
        final ByteBuffer source = ByteBuffer.allocateDirect(512);
        for (int i = 0; i < source.capacity(); i++) {
            source.put(i, (byte) (i * 3));
        }
        final JIFSFpluginJar jar = new TestJar(source);

        final ByteBuffer dest = ByteBuffer.allocate(128);
        jar.read(64, dest);

        final byte[] expected = new byte[128];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) ((64 + i) * 3);
        }
        assertArrayEquals(expected, dest.array());
        assertEquals(0, source.position());
    }

    @Test
    public void testReadClampsToEndOfFile() {
        final byte[] data = new byte[100];
        final JIFSFpluginJar jar = new TestJar(ByteBuffer.wrap(data));

        final ByteBuffer dest = ByteBuffer.allocate(256);
        jar.read(90, dest);

        assertEquals(10, dest.position());
        assertEquals(100, jar.getLength());
    }

    @Test
    public void testReadBeyondEndOfFileIsNoOp() {
        final JIFSFpluginJar jar = new TestJar(ByteBuffer.wrap(new byte[10]));

        final ByteBuffer dest = ByteBuffer.allocate(32);
        jar.read(10, dest);

        assertEquals(0, dest.position());
    }

    private static class TestJar extends JIFSFpluginJar {
        TestJar(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void refresh() {
        }
    }
}
