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

package org.jnode.net.ipv4.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.xbill.DNS.ExtendedResolver;

public class ResolverImplTest {

    @Test
    public void sendUdpWithCloseTimeoutReturnsWhenResolverDoesNotRespond() throws Exception {
        final DatagramSocket server = new DatagramSocket(0);
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch clientReturned = new CountDownLatch(1);
        final CountDownLatch callDone = new CountDownLatch(1);
        final AtomicReference<Throwable> callResult = new AtomicReference<Throwable>();
        Thread serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] buffer = new byte[512];
                    server.receive(new DatagramPacket(buffer, buffer.length));
                    received.countDown();
                    clientReturned.await();
                } catch (Exception ex) {
                    // ignored
                }
            }
        });
        Thread clientThread = new Thread(new Runnable() {
            public void run() {
                try {
                    ResolverImpl.sendUdpWithCloseTimeout(InetAddress.getByName("127.0.0.1"),
                            server.getLocalPort(), new byte[] {1}, 100);
                } catch (Throwable t) {
                    callResult.set(t);
                } finally {
                    callDone.countDown();
                }
            }
        });
        serverThread.setDaemon(true);
        clientThread.setDaemon(true);
        serverThread.start();
        clientThread.start();
        long start = System.currentTimeMillis();
        try {
            assertTrue("server did not receive UDP request", received.await(1000, TimeUnit.MILLISECONDS));
            assertTrue("sendUdpWithCloseTimeout did not return", callDone.await(1000, TimeUnit.MILLISECONDS));
            Throwable thrown = callResult.get();
            if (thrown == null) {
                fail("Expected DNS lookup timeout");
            }
            assertTrue(thrown.getMessage().indexOf("timed out") >= 0);
            assertTrue(System.currentTimeMillis() - start < 2000);
        } finally {
            clientReturned.countDown();
            server.close();
            clientThread.join(1000);
            serverThread.join(1000);
        }
    }

    @Test
    public void configureResolverAppliesTimeoutAndRetries() throws Exception {
        RecordingResolver resolver = new RecordingResolver();
        ResolverImpl.configureResolver(resolver, 3, 2);
        assertEquals(3, resolver.timeout);
        assertEquals(2, resolver.retries);
    }

    private static class RecordingResolver extends ExtendedResolver {
        private static final long serialVersionUID = 1L;

        private int timeout;
        private int retries;

        RecordingResolver() throws Exception {
            super(new String[] {"127.0.0.1"});
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
            super.setTimeout(timeout);
        }

        public void setRetries(int retries) {
            this.retries = retries;
            super.setRetries(retries);
        }
    }
}
