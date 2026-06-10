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

package org.jnode.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SocketBufferTest {

    @Test
    public void testGet32AcrossAppendedBuffers() {
        final SocketBuffer first = new SocketBuffer(new byte[] {
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78, (byte) 0x9a, (byte) 0xbc
        }, 0, 6);
        final SocketBuffer second = new SocketBuffer(new byte[] {
            (byte) 0xde, (byte) 0xf0, (byte) 0x11, (byte) 0x22
        }, 0, 4);

        first.append(second);

        assertEquals(0x9abcdef0, first.get32(4));
    }
}
