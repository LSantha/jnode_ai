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

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.SocketOptions;

import org.jnode.net.SocketBuffer;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4Header;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TCPOutChannelTest implements TCPConstants {

    private static TCPProtocol noopProtocol;

    @Test
    public void testWriteTimeoutWhenSendBufferFull() throws Exception {
        final TCPControlBlock cb = new TCPControlBlock(null, null, null, 0);
        final TCPOutChannel outChannel = new TCPOutChannel(null, cb, 0);
        fillSendBuffer(outChannel);

        final long start = System.currentTimeMillis();
        try {
            outChannel.send(new IPv4Header(0, 64, 6, IPv4Address.ANY, 1), newHeader(1), new byte[] {1}, 0, 1, 25);
            fail("Expected SocketException");
        } catch (SocketException ex) {
            assertTrue(ex.getMessage().indexOf("Write timed out") >= 0);
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 20);
            assertTrue(elapsed < 1000);
        }
    }

    @Test
    public void testWriteCompletesAfterAckFreesBuffer() throws Exception {
        final TCPProtocol protocol = newNoopProtocol();
        final TCPControlBlock cb = new TCPControlBlock(null, null, protocol, 0);
        setState(cb, TCPS_ESTABLISHED);
        final TCPSocketImpl impl = new TCPSocketImpl(protocol);
        setField(impl, "controlBlock", cb);
        final TCPOutputStream out = new TCPOutputStream(cb, impl);
        impl.setOption(SocketOptions.SO_TIMEOUT, Integer.valueOf(1000));

        out.write(new byte[TCPConstants.TCP_BUFFER_SIZE]);

        final Throwable[] failure = new Throwable[1];
        final Thread writer = new Thread(new Runnable() {
            public void run() {
                try {
                    out.write(new byte[] {1});
                } catch (Throwable ex) {
                    failure[0] = ex;
                }
            }
        });
        writer.start();
        Thread.sleep(50);
        outChannelGet(cb).processAck(TCPConstants.TCP_BUFFER_SIZE);
        writer.join(1000);

        assertFalse(writer.isAlive());
        if (failure[0] != null) {
            fail(failure[0].toString());
        }
    }

    @Test
    public void testCloseTimeoutInCloseWait() throws Exception {
        final TCPProtocol protocol = newNoopProtocol();
        final TCPControlBlock cb = new TCPControlBlock(null, null, protocol, 0);
        setState(cb, TCPS_CLOSE_WAIT);
        final TCPSocketImpl impl = new TCPSocketImpl(protocol);
        setField(impl, "controlBlock", cb);
        impl.setOption(SocketOptions.SO_TIMEOUT, Integer.valueOf(25));

        final long start = System.currentTimeMillis();
        try {
            impl.close();
            fail("Expected IOException");
        } catch (IOException ex) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 20);
            assertTrue(elapsed < 1000);
        }
    }

    private static synchronized TCPProtocol newNoopProtocol() throws Exception {
        if (noopProtocol == null) {
            noopProtocol = new TCPProtocol(null) {
                @Override
                protected void send(IPv4Header ipHdr, TCPHeader tcpHdr, SocketBuffer skbuf)
                    throws SocketException {
                }
            };
            stopTimer(noopProtocol);
        }
        return noopProtocol;
    }

    private static void stopTimer(TCPProtocol protocol) throws Exception {
        final Field timerField = TCPProtocol.class.getDeclaredField("timer");
        timerField.setAccessible(true);
        final TCPTimer timer = (TCPTimer) timerField.get(protocol);
        final Field stopField = TCPTimer.class.getDeclaredField("stop");
        stopField.setAccessible(true);
        stopField.setBoolean(timer, true);
        timer.interrupt();
    }

    private void fillSendBuffer(TCPOutChannel outChannel) throws Exception {
        final Field dataBufferField = TCPOutChannel.class.getDeclaredField("dataBuffer");
        dataBufferField.setAccessible(true);
        final TCPDataBuffer dataBuffer = (TCPDataBuffer) dataBufferField.get(outChannel);
        dataBuffer.add(new byte[TCPConstants.TCP_BUFFER_SIZE], 0, TCPConstants.TCP_BUFFER_SIZE);
    }

    private TCPOutChannel outChannelGet(TCPControlBlock cb) throws Exception {
        final Field outChannelField = TCPControlBlock.class.getDeclaredField("outChannel");
        outChannelField.setAccessible(true);
        return (TCPOutChannel) outChannelField.get(cb);
    }

    private TCPHeader newHeader(int dataLength) {
        return new TCPHeader(1, 1, dataLength, 0, 0, 65535, 0);
    }

    private void setState(TCPControlBlock cb, int state) throws Exception {
        final Field stateField = TCPControlBlock.class.getDeclaredField("curState");
        stateField.setAccessible(true);
        stateField.setInt(cb, state);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
