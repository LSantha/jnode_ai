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

package org.jnode.plugin.model;

import java.nio.ByteBuffer;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

public class PluginJarTest {

    @Test
    public void testFullArrayBufferIsReused() {
        final byte[] data = new byte[64];
        final ByteBuffer buffer = ByteBuffer.wrap(data);

        assertSame(data, PluginJar.toByteArray(buffer));
    }

    @Test
    public void testSliceIsCopied() {
        final byte[] container = new byte[128];
        for (int i = 0; i < container.length; i++) {
            container[i] = (byte) i;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(container);
        buffer.position(32);
        buffer.limit(96);

        final byte[] expected = new byte[64];
        System.arraycopy(container, 32, expected, 0, expected.length);
        assertArrayEquals(expected, PluginJar.toByteArray(buffer));
    }

    @Test
    public void testDirectBufferIsCopied() {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) (i * 2));
        }

        final byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 2);
        }
        assertArrayEquals(expected, PluginJar.toByteArray(buffer));
    }
}
