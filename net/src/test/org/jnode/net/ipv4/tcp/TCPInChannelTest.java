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

package org.jnode.net.ipv4.tcp;

import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.SocketOptions;
import java.net.SocketTimeoutException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TCPInChannelTest {

    @Test
    public void testReadTimeout() throws Exception {
        final TCPControlBlock cb = new TCPControlBlock(null, null, null, 0);
        final TCPInChannel inChannel = new TCPInChannel(cb);
        final long start = System.currentTimeMillis();

        try {
            inChannel.read(new byte[1], 0, 1, 25);
            fail("Expected SocketTimeoutException");
        } catch (SocketTimeoutException ex) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 20);
            assertTrue(elapsed < 1000);
        }
    }

    @Test
    public void testReadWithDataBeforeTimeout() throws Exception {
        final TCPControlBlock cb = new TCPControlBlock(null, null, null, 0);
        final TCPInChannel inChannel = new TCPInChannel(cb);
        final Field dataBufferField = TCPInChannel.class.getDeclaredField("dataBuffer");
        dataBufferField.setAccessible(true);
        final TCPDataBuffer dataBuffer = (TCPDataBuffer) dataBufferField.get(inChannel);
        dataBuffer.add(new byte[] {
            1, 2, 3
        }, 0, 3);

        final byte[] dst = new byte[2];
        assertEquals(2, inChannel.read(dst, 0, 2, 1000));
        assertEquals(1, dst[0]);
        assertEquals(2, dst[1]);
    }

    @Test
    public void testSocketTimeoutOption() throws SocketException {
        final TCPSocketImpl impl = new TCPSocketImpl(null);

        assertEquals(0, impl.getOption(SocketOptions.SO_TIMEOUT));

        impl.setOption(SocketOptions.SO_TIMEOUT, Integer.valueOf(123));
        assertEquals(123, impl.getOption(SocketOptions.SO_TIMEOUT));

        try {
            impl.setOption(SocketOptions.SO_TIMEOUT, Integer.valueOf(-1));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertEquals(123, impl.getOption(SocketOptions.SO_TIMEOUT));
        }
    }
}
