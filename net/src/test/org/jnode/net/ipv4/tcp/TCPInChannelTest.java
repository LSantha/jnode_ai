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

import org.jnode.net.SocketBuffer;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4Constants;
import org.jnode.net.ipv4.IPv4Header;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TCPInChannelTest implements TCPConstants {

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
    public void testOutOfOrderSegmentsAreBuffered() throws Exception {
        final TCPControlBlock cb = newControlBlockWithNoopOutChannel();
        final TCPInChannel inChannel = new TCPInChannel(cb);
        final IPv4Header ipHdr = newIpHeader(3);

        inChannel.initISN(newHeader(99, 0));
        inChannel.processData(ipHdr, newHeader(101, 2), newBuffer(new byte[] {2, 3}));

        assertEquals(0, inChannel.available());

        inChannel.processData(ipHdr, newHeader(100, 1), newBuffer(new byte[] {1}));

        final byte[] dst = new byte[3];
        assertEquals(3, inChannel.read(dst, 0, 3, 1000));
        assertBytes(new byte[] {1, 2, 3}, dst);
    }

    @Test
    public void testFutureFinIsNotMarkedReceivedUntilInOrder() throws Exception {
        final TCPControlBlock cb = newControlBlockWithNoopOutChannel();
        final TCPInChannel inChannel = new TCPInChannel(cb);
        final IPv4Header ipHdr = newIpHeader(1);
        final TCPHeader fin = newHeader(101, 0);
        fin.setFlags(TCPF_FIN);

        inChannel.initISN(newHeader(99, 0));
        inChannel.processData(ipHdr, fin, newBuffer(new byte[0]));
        assertFutureSegments(inChannel, 1);
        inChannel.processData(ipHdr, newHeader(100, 1), newBuffer(new byte[] {1}));

        assertFinReceived(inChannel);
        assertFutureSegments(inChannel, 0);

        assertEquals(1, inChannel.available());
        final byte[] dst = new byte[1];
        assertEquals(1, inChannel.read(dst, 0, 1, 10));
        assertEquals(1, dst[0]);
        assertEquals(-1, inChannel.read(dst, 0, 1, 10));
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

    private TCPControlBlock newControlBlockWithNoopOutChannel() throws Exception {
        final TCPControlBlock cb = new TCPControlBlock(null, null, null, 0);
        final Field outChannelField = TCPControlBlock.class.getDeclaredField("outChannel");
        outChannelField.setAccessible(true);
        outChannelField.set(cb, new TCPOutChannel(null, cb, 0) {
            @Override
            public void send(IPv4Header ipHdr, TCPHeader hdr) throws SocketException {
                // Ignore ACKs in this unit test.
            }
        });
        return cb;
    }

    private IPv4Header newIpHeader(int dataLength) {
        return new IPv4Header(0, 64, IPv4Constants.IPPROTO_TCP, new IPv4Address("127.0.0.1"), dataLength);
    }

    private TCPHeader newHeader(int seqNr, int dataLength) {
        return new TCPHeader(1, 1, dataLength, seqNr, 0, 65535, 0);
    }

    private SocketBuffer newBuffer(byte[] data) {
        return new SocketBuffer(data, 0, data.length);
    }

    private void assertFutureSegments(TCPInChannel inChannel, int expected) throws Exception {
        final Field futureSegmentsField = TCPInChannel.class.getDeclaredField("futureSegments");
        futureSegmentsField.setAccessible(true);
        assertEquals(expected, ((java.util.List<?>) futureSegmentsField.get(inChannel)).size());
    }

    private void assertFinReceived(TCPInChannel inChannel) throws Exception {
        final Field finReceivedField = TCPInChannel.class.getDeclaredField("finReceived");
        finReceivedField.setAccessible(true);
        assertEquals(Boolean.TRUE, finReceivedField.get(inChannel));
    }

    private void assertBytes(byte[] expected, byte[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i]);
        }
    }
}
